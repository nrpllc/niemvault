package gov.niemplatform.exchange.api;

import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * What this platform sends to an external system of record, as a versioned artifact (ADR 0034).
 *
 * <p>The outbound mirror of a source definition (ADR 0029), and it exists for the same reason. The
 * first criminal history writer named three canonical types in {@code static final String}
 * constants and listed twelve fields one Java line at a time, which meant that adding a charge to a
 * submission was a code change and a platform release. Spec §0.5 says every mapping, schema and
 * transformation is a versioned artifact; a submission is all three at once and was the one that
 * was not.
 *
 * <p>So an exchange is a file:
 *
 * <pre>{@code
 * exchange: fdle-cch-arrest
 * version: "1.0.0"
 * type: cch-http
 * sourceId: leon-so-cad
 *
 * assemble:
 *   root: Arrest
 *   follow:
 *     - association: ArrestSubjectAssociation
 *       role: person
 *       as: subject
 *
 * settings:
 *   endpoint: https://cch.fdle.example/submit
 * }</pre>
 *
 * <p><strong>Assembly and transport only.</strong> The same boundary {@code SourceDefinition}
 * draws, from the other side: this says what is gathered and where it goes, never how a field is
 * spelled on the wire. Field-level work belongs to the exchange's own artifacts and uses the
 * mapping layer's step vocabulary, so there is one transform language in this platform rather than
 * a second one written in Java for outbound.
 *
 * <p>Strict on unrecognised keys (ADR 0010). A misspelled {@code asemble} that was silently ignored
 * would submit an empty document, and a repository accepts an empty optional element without
 * complaint.
 */
public record ExchangeDefinition(
        String exchangeName,
        String version,
        ExchangeType type,
        String sourceId,
        AssemblySpec assemble,
        Map<String, String> settings) {

    private static final Set<String> ROOT_KEYS =
            Set.of("exchange", "version", "type", "sourceId", "assemble", "settings");
    private static final Set<String> ASSEMBLE_KEYS = Set.of("root", "follow");
    private static final Set<String> FOLLOW_KEYS = Set.of("association", "role", "as", "follow");

    public ExchangeDefinition {
        Objects.requireNonNull(exchangeName, "exchangeName");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(assemble, "assemble");
        settings = Map.copyOf(settings);
    }

    /** A setting the exchange writer requires. */
    public String requiredSetting(String key) {
        String value = settings.get(key);
        if (value == null || value.isBlank()) {
            throw new ExchangeDefinitionException(null,
                    List.of("exchange '" + exchangeName + "' needs a '" + key + "' setting"));
        }
        return value.trim();
    }

    /** A setting that may be absent. */
    public Optional<String> setting(String key) {
        return Optional.ofNullable(settings.get(key)).map(String::trim).filter(v -> !v.isEmpty());
    }

    /**
     * Reads and validates an exchange definition.
     *
     * @param file the artifact
     * @param model the canonical model to validate the assembly against, so a misspelled role is an
     *     error here rather than an empty element in a submitted document
     * @throws ExchangeDefinitionException naming every problem at once
     */
    public static ExchangeDefinition load(Path file, List<CanonicalTypeDescriptor> model) {
        List<String> problems = new ArrayList<>();
        Object parsed;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            parsed = new Yaml(new SafeConstructor(options))
                    .load(Files.newBufferedReader(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ExchangeDefinitionException(file, List.of("cannot be read: " + e.getMessage()));
        } catch (RuntimeException e) {
            throw new ExchangeDefinitionException(file,
                    List.of("is not valid YAML: " + e.getMessage()));
        }

        if (!(parsed instanceof Map<?, ?> document)) {
            throw new ExchangeDefinitionException(file,
                    List.of("must be a YAML mapping at the top level"));
        }

        Map<String, Object> root = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        document.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (ROOT_KEYS.contains(name)) {
                root.put(name, value);
            } else {
                unknown.add(name);
            }
        });
        unknown.forEach(key -> problems.add("unrecognised key '" + key
                + "'; an exchange definition accepts " + ROOT_KEYS.stream().sorted().toList()));

        String name = requiredText(root, "exchange", problems);
        String version = requiredText(root, "version", problems);
        String sourceId = requiredText(root, "sourceId", problems);
        String typeId = requiredText(root, "type", problems);

        ExchangeType exchangeType = null;
        if (typeId != null) {
            try {
                exchangeType = ExchangeType.of(typeId);
            } catch (IllegalArgumentException e) {
                problems.add("'type': " + e.getMessage());
            }
        }
        if (version != null && !version.matches("\\d+\\.\\d+\\.\\d+")) {
            problems.add("'version' must be semver (MAJOR.MINOR.PATCH), found '" + version + "'");
        }

        AssemblySpec assemble = readAssemble(root.get("assemble"), problems);

        Map<String, String> settings = new LinkedHashMap<>();
        Object declaredSettings = root.get("settings");
        if (declaredSettings instanceof Map<?, ?> settingsMap) {
            settingsMap.forEach((key, value) -> {
                if (value instanceof Map || value instanceof List) {
                    problems.add("setting '" + key + "' must be a scalar; an exchange reads its "
                            + "settings as text");
                } else if (value != null) {
                    settings.put(String.valueOf(key), String.valueOf(value));
                }
            });
        } else if (declaredSettings != null) {
            problems.add("'settings' must be a mapping of setting names to values");
        }

        // The assembly is checked against the model only once it is structurally sound; reporting
        // "root is not a canonical type" on top of "root is missing" helps nobody.
        if (assemble != null) {
            assemble.validate(model).forEach(problem ->
                    problems.add(problem.at() + ": " + problem.detail()));
        }

        if (!problems.isEmpty()) {
            throw new ExchangeDefinitionException(file, problems);
        }
        return new ExchangeDefinition(name, version, exchangeType, sourceId, assemble, settings);
    }

    private static AssemblySpec readAssemble(Object declared, List<String> problems) {
        if (declared == null) {
            problems.add("'assemble' is required: an exchange that gathers nothing sends an empty "
                    + "document, which a repository accepts without complaint");
            return null;
        }
        if (!(declared instanceof Map<?, ?> block)) {
            problems.add("'assemble' must be a mapping with 'root' and optionally 'follow'");
            return null;
        }
        rejectUnknown(block, ASSEMBLE_KEYS, "assemble", problems);

        Object root = block.get("root");
        if (root == null || String.valueOf(root).isBlank()) {
            problems.add("'assemble.root' is required: a document is rooted on one canonical type");
            return null;
        }
        List<AssemblySpec.Follow> follows =
                readFollows(block.get("follow"), "assemble.follow", problems);
        return new AssemblySpec(String.valueOf(root).trim(), follows);
    }

    private static List<AssemblySpec.Follow> readFollows(
            Object declared, String at, List<String> problems) {

        if (declared == null) {
            return List.of();
        }
        if (!(declared instanceof List<?> entries)) {
            problems.add("'" + at + "' must be a list of association steps");
            return List.of();
        }
        List<AssemblySpec.Follow> follows = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            String where = at + "[" + index + "]";
            if (!(entries.get(index) instanceof Map<?, ?> step)) {
                problems.add("'" + where + "' must be a mapping");
                continue;
            }
            rejectUnknown(step, FOLLOW_KEYS, where, problems);

            String association = text(step.get("association"));
            String role = text(step.get("role"));
            if (association == null) {
                problems.add("'" + where + ".association' is required");
            }
            if (role == null) {
                problems.add("'" + where + ".role' is required: an association has two ends and "
                        + "this says which one is being pulled in");
            }
            // Defaults to the role name, because that is what it is nearly always called and a
            // required 'as' would be boilerplate on every step.
            String as = text(step.get("as")) != null ? text(step.get("as")) : role;
            if (association == null || role == null) {
                continue;
            }
            follows.add(new AssemblySpec.Follow(association, role, as,
                    readFollows(step.get("follow"), where + ".follow", problems)));
        }
        return follows;
    }

    private static void rejectUnknown(
            Map<?, ?> block, Set<String> allowed, String at, List<String> problems) {
        block.keySet().stream()
                .map(String::valueOf)
                .filter(key -> !allowed.contains(key))
                .forEach(key -> problems.add("unrecognised key '" + at + "." + key
                        + "'; accepted here: " + allowed.stream().sorted().toList()));
    }

    private static String text(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value).trim();
    }

    private static String requiredText(Map<String, Object> root, String key, List<String> problems) {
        Object value = root.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            problems.add("'" + key + "' is required");
            return null;
        }
        return String.valueOf(value).trim();
    }

    /** Keys only -- never values. A settings map routinely holds a token. See ADR 0015. */
    @Override
    public String toString() {
        return "ExchangeDefinition[" + exchangeName + "@" + version + ", type=" + type
                + ", source=" + sourceId + ", root=" + assemble.rootType()
                + ", settingKeys=" + settings.keySet() + "]";
    }
}
