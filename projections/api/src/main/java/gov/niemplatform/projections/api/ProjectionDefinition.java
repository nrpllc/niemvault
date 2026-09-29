package gov.niemplatform.projections.api;

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
 * Where gold is written, as a versioned artifact (ADR 0035).
 *
 * <p>The same move ADR 0029 made for a source and ADR 0034 made for an exchange. A projection used
 * to be a flag per backend -- {@code --neo4j-uri} on replay, and nothing at all on ingest -- which
 * meant a second projection was a second set of flags on every command that writes gold, and a
 * third was a code change to each of them. A file names the backend and how to reach it; the
 * registry finds the writer.
 *
 * <pre>{@code
 * projection: leon-ods
 * version: "1.0.0"
 * type: ods
 * settings:
 *   jdbcUrl: jdbc:postgresql://localhost:15432/niem
 *   user: niem
 *   passwordEnv: NIEM_ODS_PASSWORD
 * }</pre>
 *
 * <p><strong>Where, never what.</strong> A projection is gold, and gold is all of silver in another
 * shape (§4.6). A definition that could select types or rename fields would make one projection a
 * different answer from another about the same records, which is the divergence §4.7 exists to
 * detect -- so there is nowhere here to say it.
 *
 * <p>Settings are scalar text, and a credential is named by the environment variable that holds
 * it, never written here (ADR 0015). Strict on unrecognised keys (ADR 0010): a misspelled
 * {@code setings} that was silently ignored would open a writer against its defaults, which for a
 * database means somebody else's.
 */
public record ProjectionDefinition(
        String projectionName,
        String version,
        ProjectionType type,
        Map<String, String> settings) {

    private static final Set<String> ROOT_KEYS = Set.of("projection", "version", "type", "settings");

    public ProjectionDefinition {
        Objects.requireNonNull(projectionName, "projectionName");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(type, "type");
        settings = Map.copyOf(settings);
    }

    /** A setting the writer cannot open without. */
    public String requiredSetting(String key) {
        return setting(key).orElseThrow(() -> new ProjectionDefinitionException(null, List.of(
                "projection '" + projectionName + "' needs a '" + key + "' setting")));
    }

    /** A setting that may be absent. */
    public Optional<String> setting(String key) {
        return Optional.ofNullable(settings.get(key)).map(String::trim).filter(v -> !v.isEmpty());
    }

    /**
     * A secret, read from the environment variable a setting names.
     *
     * <p>Absent when the setting is absent. When the setting names a variable that is not set, that
     * is an error rather than an absence: an operator who wrote {@code passwordEnv} meant a password
     * to be used, and connecting without one reaches a different account or none.
     */
    public Optional<String> secret(String key, java.util.function.Function<String, String> environment) {
        Optional<String> variable = setting(key);
        if (variable.isEmpty()) {
            return Optional.empty();
        }
        String value = environment.apply(variable.get());
        if (value == null || value.isEmpty()) {
            throw new ProjectionDefinitionException(null, List.of(
                    "projection '" + projectionName + "' reads its '" + key + "' from $"
                            + variable.get() + ", which is not set"));
        }
        return Optional.of(value);
    }

    /** "name@version", for reports and lineage. */
    public String qualifiedName() {
        return projectionName + "@" + version;
    }

    /**
     * Reads and validates a projection definition.
     *
     * @throws ProjectionDefinitionException naming every problem at once
     */
    public static ProjectionDefinition load(Path file) {
        Object parsed;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            parsed = new Yaml(new SafeConstructor(options))
                    .load(Files.newBufferedReader(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ProjectionDefinitionException(file, List.of("cannot be read: " + e.getMessage()));
        } catch (RuntimeException e) {
            throw new ProjectionDefinitionException(file,
                    List.of("is not valid YAML: " + e.getMessage()));
        }
        if (!(parsed instanceof Map<?, ?> document)) {
            throw new ProjectionDefinitionException(file,
                    List.of("must be a YAML mapping at the top level"));
        }

        List<String> problems = new ArrayList<>();
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
                + "'; a projection definition accepts " + ROOT_KEYS.stream().sorted().toList()));

        String name = requiredText(root, "projection", problems);
        String version = requiredText(root, "version", problems);
        String typeId = requiredText(root, "type", problems);

        ProjectionType type = null;
        if (typeId != null) {
            try {
                type = ProjectionType.of(typeId);
            } catch (IllegalArgumentException e) {
                problems.add("'type': " + e.getMessage());
            }
        }
        if (version != null && !version.matches("\\d+\\.\\d+\\.\\d+")) {
            problems.add("'version' must be semver (MAJOR.MINOR.PATCH), found '" + version + "'");
        }

        Map<String, String> settings = new LinkedHashMap<>();
        Object declared = root.get("settings");
        if (declared instanceof Map<?, ?> settingsMap) {
            settingsMap.forEach((key, value) -> {
                if (value instanceof Map || value instanceof List) {
                    problems.add("setting '" + key + "' must be a scalar; a projection reads its "
                            + "settings as text");
                } else if (value != null) {
                    settings.put(String.valueOf(key), String.valueOf(value));
                }
            });
        } else if (declared != null) {
            problems.add("'settings' must be a mapping of setting names to values");
        }

        if (!problems.isEmpty()) {
            throw new ProjectionDefinitionException(file, problems);
        }
        return new ProjectionDefinition(name, version, type, settings);
    }

    private static String requiredText(Map<String, Object> root, String key, List<String> problems) {
        Object value = root.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            problems.add("'" + key + "' is required");
            return null;
        }
        return String.valueOf(value).trim();
    }

    /** Keys only -- never values. A settings map may name where a secret lives. See ADR 0015. */
    @Override
    public String toString() {
        return "ProjectionDefinition[" + qualifiedName() + ", type=" + type
                + ", settingKeys=" + settings.keySet() + "]";
    }
}
