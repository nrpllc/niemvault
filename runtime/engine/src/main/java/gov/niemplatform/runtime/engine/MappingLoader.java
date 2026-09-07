package gov.niemplatform.runtime.engine;

import gov.niemplatform.runtime.engine.MappingLoadException.Code;
import gov.niemplatform.runtime.engine.MappingLoadException.Problem;
import gov.niemplatform.runtime.transforms.TransformFactory;
import gov.niemplatform.runtime.transforms.TransformSpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads a mapping from a YAML artifact (spec §5: mappings are data, not code).
 *
 * <p>This is what makes the §5 requirement real -- "an agency updating a mapping must not require
 * a platform rebuild". Without a loader the {@code MappingDefinition} model would be a Java DSL
 * wearing a data model's clothes.
 *
 * <p>Strict, for the same reason everywhere else is (ADR 0010): a misspelled key that loads
 * happily produces a mapping that silently does something other than what it says. Transform
 * specs are compiled during load rather than on the first record, so a bad regex or an unknown
 * transform type fails the deployment instead of the nightly run.
 */
public final class MappingLoader {

    private static final Set<String> ROOT_KEYS = Set.of("mapping", "version", "source", "decode", "hops");
    private static final Set<String> DECODE_KEYS =
            Set.of("format", "emits", "columns", "delimiter", "quote", "charset", "trimValues");
    private static final Set<String> HOP_KEYS =
            Set.of("id", "contract", "version", "dependsOn", "steps", "identity", "roles", "scratch");
    private static final Set<String> STEP_KEYS = Set.of("target", "type", "from", "options");
    private static final Set<String> IDENTITY_KEYS =
            Set.of("mode", "entityType", "provider", "attributes", "deriveFrom", "prefix");

    private final List<Problem> problems = new ArrayList<>();

    /** Loads a mapping from a file. */
    public MappingDefinition load(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return load(reader, file.getFileName().toString());
        } catch (IOException e) {
            throw new MappingLoadException(List.of(
                    new Problem(file.toString(), "<root>", Code.UNREADABLE, e.getMessage())));
        }
    }

    /** Loads a mapping from a stream, e.g. a classpath resource in a domain module. */
    public MappingDefinition load(InputStream input, String sourceName) {
        return load(new InputStreamReader(input, StandardCharsets.UTF_8), sourceName);
    }

    private MappingDefinition load(Reader reader, String source) {
        problems.clear();

        Map<String, Object> root;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object loaded = new Yaml(new SafeConstructor(options)).load(reader);
            if (!(loaded instanceof Map<?, ?> map)) {
                throw new MappingLoadException(List.of(new Problem(source, "<root>",
                        Code.MALFORMED_YAML, "expected a mapping at the document root")));
            }
            root = cast(map);
        } catch (MappingLoadException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MappingLoadException(List.of(
                    new Problem(source, "<root>", Code.MALFORMED_YAML, String.valueOf(e.getMessage()))));
        }

        rejectUnknownKeys(source, "<root>", root.keySet(), ROOT_KEYS);

        String name = requireString(source, "<root>", root, "mapping");
        String version = requireString(source, "<root>", root, "version");
        String sourceId = requireString(source, "<root>", root, "source");
        DecoderSpec decoder = parseDecoder(source, root.get("decode"));
        List<HopDefinition> hops = parseHops(source, root.get("hops"));

        if (!problems.isEmpty()) {
            throw new MappingLoadException(problems);
        }
        try {
            return new MappingDefinition(name, version, sourceId, decoder, hops);
        } catch (IllegalArgumentException e) {
            // MappingDefinition validates the hop graph itself; surface that as a load problem
            // rather than letting a raw IllegalArgumentException escape.
            throw new MappingLoadException(List.of(
                    new Problem(source, "<root>", Code.GRAPH, e.getMessage())));
        }
    }

    // --- decoder ---------------------------------------------------------

    private DecoderSpec parseDecoder(String source, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            problems.add(new Problem(source, "decode", Code.MISSING_KEY,
                    "'decode' is required and must be a mapping"));
            return null;
        }
        Map<String, Object> block = cast(map);
        rejectUnknownKeys(source, "decode", block.keySet(), DECODE_KEYS);

        String format = optionalString(block, "format");
        String emits = requireString(source, "decode", block, "emits");
        List<String> columns = stringList(source, "decode", block, "columns");
        if (columns.isEmpty()) {
            problems.add(new Problem(source, "decode", Code.MISSING_KEY,
                    "'columns' is required; a header row is data and must not be trusted to name fields"));
        }
        if (emits == null || columns.isEmpty()) {
            return null;
        }
        try {
            return new DecoderSpec(
                    format == null ? DecoderSpec.FORMAT_DELIMITED : format,
                    emits,
                    columns,
                    singleChar(source, "decode", block, "delimiter", ','),
                    singleChar(source, "decode", block, "quote", '"'),
                    optionalString(block, "charset") == null ? "UTF-8" : optionalString(block, "charset"),
                    booleanValue(source, "decode", block, "trimValues", true));
        } catch (IllegalArgumentException e) {
            problems.add(new Problem(source, "decode", Code.INVALID_VALUE, e.getMessage()));
            return null;
        }
    }

    // --- hops ------------------------------------------------------------

    private List<HopDefinition> parseHops(String source, Object raw) {
        List<HopDefinition> hops = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            problems.add(new Problem(source, "hops", Code.MISSING_KEY,
                    "'hops' is required and must be a list"));
            return hops;
        }
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> entry)) {
                problems.add(new Problem(source, "hops[" + i + "]", Code.INVALID_VALUE,
                        "each hop must be a mapping"));
                continue;
            }
            HopDefinition hop = parseHop(source, cast(entry), i);
            if (hop != null) {
                hops.add(hop);
            }
        }
        return hops;
    }

    private HopDefinition parseHop(String source, Map<String, Object> block, int position) {
        String hopId = optionalString(block, "id");
        String location = "hops[" + (hopId == null ? position : hopId) + "]";
        rejectUnknownKeys(source, location, block.keySet(), HOP_KEYS);

        if (hopId == null) {
            problems.add(new Problem(source, location, Code.MISSING_KEY, "'id' is required"));
            return null;
        }
        String contract = requireString(source, location, block, "contract");
        String contractVersion = requireString(source, location, block, "version");
        List<String> dependsOn = stringList(source, location, block, "dependsOn");
        List<String> scratch = stringList(source, location, block, "scratch");
        List<TransformSpec> steps = parseSteps(source, location, block.get("steps"));
        IdentitySpec identity = parseIdentity(source, location, block.get("identity"));
        Map<String, String> roles = stringMap(source, location, block, "roles");

        if (contract == null || contractVersion == null || identity == null) {
            return null;
        }
        checkTargetsDeclared(source, location, steps, scratch, roles);

        try {
            return new HopDefinition(hopId, contract, contractVersion, dependsOn, steps,
                    identity, roles, scratch);
        } catch (IllegalArgumentException e) {
            problems.add(new Problem(source, location, Code.GRAPH, e.getMessage()));
            return null;
        }
    }

    /**
     * Every step target must be something the hop means to emit, or a declared scratch field.
     *
     * <p>Without this, a typo in a step target silently produces a field nobody reads -- and if
     * the intended field is optional, nothing downstream notices. Checked here rather than at the
     * output gate because a load-time failure names the mapping and the line.
     */
    private void checkTargetsDeclared(String source, String location, List<TransformSpec> steps,
            List<String> scratch, Map<String, String> roles) {
        Set<String> declaredScratch = Set.copyOf(scratch);
        Set<String> targets = new LinkedHashSet<>();
        steps.forEach(step -> targets.add(step.target()));

        for (String scratchField : declaredScratch) {
            if (!targets.contains(scratchField)) {
                problems.add(new Problem(source, location, Code.UNDECLARED_TARGET,
                        "'" + scratchField + "' is declared as scratch but no step writes it"));
            }
        }
        for (String role : roles.keySet()) {
            if (targets.contains(role)) {
                problems.add(new Problem(source, location, Code.INVALID_VALUE,
                        "'" + role + "' is an association role; a step must not also write it"));
            }
        }
    }

    private List<TransformSpec> parseSteps(String source, String hopLocation, Object raw) {
        List<TransformSpec> steps = new ArrayList<>();
        if (raw == null) {
            return steps;
        }
        if (!(raw instanceof List<?> list)) {
            problems.add(new Problem(source, hopLocation, Code.INVALID_VALUE, "'steps' must be a list"));
            return steps;
        }
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> entry)) {
                problems.add(new Problem(source, hopLocation + ".steps[" + i + "]",
                        Code.INVALID_VALUE, "each step must be a mapping"));
                continue;
            }
            Map<String, Object> block = cast(entry);
            String target = optionalString(block, "target");
            String location = hopLocation + ".steps[" + (target == null ? i : target) + "]";
            rejectUnknownKeys(source, location, block.keySet(), STEP_KEYS);

            String type = optionalString(block, "type");
            if (target == null || type == null) {
                problems.add(new Problem(source, location, Code.MISSING_KEY,
                        "'target' and 'type' are both required"));
                continue;
            }
            TransformSpec spec = new TransformSpec(target, type,
                    stringList(source, location, block, "from"),
                    stringMap(source, location, block, "options"));
            try {
                // Compiled now, not on the first record: a bad regex or an unknown transform is a
                // deployment problem and must fail the deployment.
                TransformFactory.create(spec);
                steps.add(spec);
            } catch (IllegalArgumentException e) {
                problems.add(new Problem(source, location, Code.UNKNOWN_TRANSFORM, e.getMessage()));
            }
        }
        return steps;
    }

    private IdentitySpec parseIdentity(String source, String hopLocation, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            problems.add(new Problem(source, hopLocation, Code.MISSING_KEY,
                    "'identity' is required and must be a mapping"));
            return null;
        }
        Map<String, Object> block = cast(map);
        String location = hopLocation + ".identity";
        rejectUnknownKeys(source, location, block.keySet(), IDENTITY_KEYS);

        String mode = optionalString(block, "mode");
        String entityType = requireString(source, location, block, "entityType");
        if (mode == null || entityType == null) {
            if (mode == null) {
                problems.add(new Problem(source, location, Code.MISSING_KEY,
                        "'mode' is required and must be 'resolve' or 'derive'"));
            }
            return null;
        }
        try {
            return switch (mode) {
                case "resolve" -> IdentitySpec.resolve(entityType,
                        requireString(source, location, block, "provider"),
                        stringMap(source, location, block, "attributes"));
                case "derive" -> IdentitySpec.derive(entityType,
                        stringList(source, location, block, "deriveFrom"),
                        optionalString(block, "prefix"));
                default -> {
                    problems.add(new Problem(source, location, Code.INVALID_VALUE,
                            "'mode' must be 'resolve' or 'derive', found '" + mode + "'"));
                    yield null;
                }
            };
        } catch (IllegalArgumentException | NullPointerException e) {
            problems.add(new Problem(source, location, Code.INVALID_VALUE, String.valueOf(e.getMessage())));
            return null;
        }
    }

    // --- primitives ------------------------------------------------------

    private void rejectUnknownKeys(String source, String location, Set<String> present, Set<String> allowed) {
        Set<String> unknown = new LinkedHashSet<>(present);
        unknown.removeAll(allowed);
        for (String key : unknown) {
            problems.add(new Problem(source, location, Code.UNKNOWN_KEY,
                    "unrecognised key '" + key + "'; allowed keys are " + allowed.stream().sorted().toList()));
        }
    }

    private String requireString(String source, String location, Map<String, Object> map, String key) {
        String value = optionalString(map, key);
        if (value == null) {
            problems.add(new Problem(source, location, Code.MISSING_KEY, "'" + key + "' is required"));
        }
        return value;
    }

    private static String optionalString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private char singleChar(String source, String location, Map<String, Object> map, String key, char fallback) {
        String value = optionalString(map, key);
        if (value == null) {
            return fallback;
        }
        if (value.length() != 1) {
            problems.add(new Problem(source, location, Code.INVALID_VALUE,
                    "'" + key + "' must be a single character, found '" + value + "'"));
            return fallback;
        }
        return value.charAt(0);
    }

    private boolean booleanValue(String source, String location, Map<String, Object> map,
            String key, boolean fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        problems.add(new Problem(source, location, Code.INVALID_VALUE,
                "'" + key + "' must be a boolean, found '" + value + "'"));
        return fallback;
    }

    private List<String> stringList(String source, String location, Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            problems.add(new Problem(source, location, Code.INVALID_VALUE, "'" + key + "' must be a list"));
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private Map<String, String> stringMap(String source, String location, Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> nested)) {
            problems.add(new Problem(source, location, Code.INVALID_VALUE, "'" + key + "' must be a mapping"));
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        nested.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
