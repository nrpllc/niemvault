package gov.niemplatform.contracts;

import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.canonical.meta.CanonicalTypeResolver;
import gov.niemplatform.canonical.meta.FieldType;
import gov.niemplatform.contracts.ContractLoadException.Code;
import gov.niemplatform.contracts.ContractLoadException.Problem;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.PatternSyntaxException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads hop contracts from YAML artifacts (spec §4.2: contracts are versioned artifacts on disk,
 * not code).
 *
 * <p>Strict, for the same reason the canonical DSL is (ADR 0010, ADR 0012): an unrecognised key
 * is an error. A contract with a misspelled {@code requird: true} that loads happily is a
 * contract that silently stops checking the thing it was written to check.
 *
 * <p>A contract's {@code emits} side may name a canonical type instead of restating its fields.
 * That is the preferred form -- a canonical schema derived from the model cannot drift from it,
 * whereas a hand-copied field list will.
 */
public final class ContractLoader {

    private static final Set<String> ROOT_KEYS = Set.of("contract", "version", "hop", "expects", "emits");
    private static final Set<String> SCHEMA_KEYS =
            Set.of("schema", "version", "allowUnexpectedFields", "fields", "canonicalType");
    private static final Set<String> FIELD_KEYS =
            Set.of("name", "type", "required", "repeated", "codeList", "refType", "pattern");

    private final CanonicalTypeResolver canonicalTypes;
    private final List<Problem> problems = new ArrayList<>();

    public ContractLoader(CanonicalTypeResolver canonicalTypes) {
        this.canonicalTypes = Objects.requireNonNull(canonicalTypes, "canonicalTypes");
    }

    /** Loads every {@code *.yaml} contract under a directory. */
    public List<HopContract> loadDirectory(Path directory) {
        problems.clear();
        List<HopContract> contracts = new ArrayList<>();

        if (!Files.isDirectory(directory)) {
            problems.add(new Problem(directory.toString(), "<root>", Code.UNREADABLE,
                    "not a directory"));
            throw new ContractLoadException(problems);
        }

        List<Path> files;
        try (var stream = Files.walk(directory)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            problems.add(new Problem(directory.toString(), "<root>", Code.UNREADABLE, e.getMessage()));
            throw new ContractLoadException(problems);
        }

        Set<String> seen = new LinkedHashSet<>();
        for (Path file : files) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                HopContract contract = parse(reader, file.getFileName().toString());
                if (contract != null) {
                    if (!seen.add(contract.id().toString())) {
                        problems.add(new Problem(file.getFileName().toString(), contract.id().toString(),
                                Code.DUPLICATE_CONTRACT, "this contract name and version is already loaded"));
                    } else {
                        contracts.add(contract);
                    }
                }
            } catch (IOException e) {
                problems.add(new Problem(file.getFileName().toString(), "<root>", Code.UNREADABLE,
                        e.getMessage()));
            }
        }

        if (!problems.isEmpty()) {
            throw new ContractLoadException(problems);
        }
        return List.copyOf(contracts);
    }

    /** Loads a single contract from a stream, e.g. a classpath resource. */
    public HopContract load(InputStream input, String sourceName) {
        problems.clear();
        HopContract contract = parse(new java.io.InputStreamReader(input, StandardCharsets.UTF_8), sourceName);
        if (!problems.isEmpty()) {
            throw new ContractLoadException(problems);
        }
        return contract;
    }

    // --- parsing ---------------------------------------------------------

    private HopContract parse(Reader reader, String source) {
        Map<String, Object> root;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object loaded = new Yaml(new SafeConstructor(options)).load(reader);
            if (!(loaded instanceof Map<?, ?> map)) {
                problems.add(new Problem(source, "<root>", Code.MALFORMED_YAML,
                        "expected a mapping at the document root"));
                return null;
            }
            root = cast(map);
        } catch (RuntimeException e) {
            problems.add(new Problem(source, "<root>", Code.MALFORMED_YAML, String.valueOf(e.getMessage())));
            return null;
        }

        rejectUnknownKeys(source, "<root>", root.keySet(), ROOT_KEYS);

        String name = requireString(source, "<root>", root, "contract");
        String version = requireString(source, "<root>", root, "version");
        String hop = requireString(source, "<root>", root, "hop");

        Schema expects = parseSchema(source, "expects", root.get("expects"));
        Schema emits = parseSchema(source, "emits", root.get("emits"));

        if (name == null || version == null || hop == null || expects == null || emits == null) {
            return null;
        }
        try {
            return new SchemaHopContract(ContractId.of(name, version), hop, expects, emits);
        } catch (IllegalArgumentException e) {
            problems.add(new Problem(source, "<root>", Code.INVALID_VALUE, e.getMessage()));
            return null;
        }
    }

    private Schema parseSchema(String source, String side, Object raw) {
        if (raw == null) {
            problems.add(new Problem(source, side, Code.MISSING_KEY, "'" + side + "' is required"));
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            problems.add(new Problem(source, side, Code.INVALID_VALUE, "'" + side + "' must be a mapping"));
            return null;
        }
        Map<String, Object> block = cast(map);
        rejectUnknownKeys(source, side, block.keySet(), SCHEMA_KEYS);

        String canonicalType = optionalString(block, "canonicalType");
        if (canonicalType != null) {
            return derivedFromCanonical(source, side, block, canonicalType);
        }

        String id = requireString(source, side, block, "schema");
        String version = requireString(source, side, block, "version");
        List<FieldExpectation> fields = parseFields(source, side, block.get("fields"));
        boolean tolerant = booleanValue(source, side, block, "allowUnexpectedFields", false);

        if (id == null || version == null) {
            return null;
        }
        try {
            return new Schema(id, version, fields, tolerant);
        } catch (IllegalArgumentException e) {
            problems.add(new Problem(source, side, Code.INVALID_VALUE, e.getMessage()));
            return null;
        }
    }

    private Schema derivedFromCanonical(String source, String side, Map<String, Object> block, String typeName) {
        for (String conflicting : List.of("schema", "fields", "version")) {
            if (block.containsKey(conflicting)) {
                problems.add(new Problem(source, side, Code.INVALID_VALUE,
                        "'canonicalType' derives the whole schema from the model; '" + conflicting
                                + "' must not also be set"));
            }
        }
        Optional<CanonicalTypeDescriptor> descriptor = canonicalTypes.byName(typeName);
        if (descriptor.isEmpty()) {
            problems.add(new Problem(source, side, Code.UNRESOLVED_CANONICAL_TYPE,
                    "no canonical type named '" + typeName + "' is loaded"));
            return null;
        }
        Schema derived = Schema.ofCanonical(descriptor.get());
        return booleanValue(source, side, block, "allowUnexpectedFields", false)
                ? derived.tolerantOfUnexpectedFields()
                : derived;
    }

    private List<FieldExpectation> parseFields(String source, String side, Object raw) {
        List<FieldExpectation> fields = new ArrayList<>();
        if (raw == null) {
            problems.add(new Problem(source, side, Code.MISSING_KEY,
                    "'fields' is required unless 'canonicalType' is used"));
            return fields;
        }
        if (!(raw instanceof List<?> list)) {
            problems.add(new Problem(source, side, Code.INVALID_VALUE, "'fields' must be a list"));
            return fields;
        }
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> entry)) {
                problems.add(new Problem(source, side + ".fields[" + i + "]", Code.INVALID_VALUE,
                        "each field must be a mapping"));
                continue;
            }
            Map<String, Object> field = cast(entry);
            String fieldName = optionalString(field, "name");
            String location = side + "." + (fieldName == null ? "fields[" + i + "]" : fieldName);
            rejectUnknownKeys(source, location, field.keySet(), FIELD_KEYS);

            if (fieldName == null) {
                problems.add(new Problem(source, location, Code.MISSING_KEY, "'name' is required"));
                continue;
            }
            FieldType type = parseType(source, location, field);
            if (type == null) {
                continue;
            }
            String pattern = optionalString(field, "pattern");
            try {
                fields.add(new FieldExpectation(
                        fieldName,
                        type,
                        booleanValue(source, location, field, "required", false),
                        booleanValue(source, location, field, "repeated", false),
                        stringList(source, location, field, "codeList"),
                        optionalString(field, "refType"),
                        pattern));
            } catch (PatternSyntaxException e) {
                problems.add(new Problem(source, location, Code.INVALID_VALUE,
                        "'pattern' is not a valid regular expression: " + e.getDescription()));
            }
        }
        return fields;
    }

    private FieldType parseType(String source, String location, Map<String, Object> field) {
        String raw = optionalString(field, "type");
        if (raw == null) {
            problems.add(new Problem(source, location, Code.MISSING_KEY, "'type' is required"));
            return null;
        }
        return switch (raw) {
            case "string" -> FieldType.STRING;
            case "code" -> FieldType.CODE;
            case "date" -> FieldType.DATE;
            case "dateTime" -> FieldType.DATE_TIME;
            case "integer" -> FieldType.INTEGER;
            case "decimal" -> FieldType.DECIMAL;
            case "boolean" -> FieldType.BOOLEAN;
            case "ref" -> FieldType.REF;
            default -> {
                problems.add(new Problem(source, location, Code.INVALID_VALUE,
                        "unknown field type '" + raw + "'"));
                yield null;
            }
        };
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
