package gov.niemplatform.connectors.api;

import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
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
 * A source's transport configuration as a versioned artifact on disk (spec §0.5, §4.3).
 *
 * <p>The reason this exists rather than a flag per transport: the operator CLI knew how to build a
 * {@code ConnectorConfig} for exactly one connector, with {@code --drop} and {@code --pattern}
 * hard-coded into the {@code run} command. That works while there is one transport and stops
 * working at two. A Kafka source needs a broker, a topic, a group and a retention posture; a CDC
 * source will need a log position and a table list; an FTP source a host, a path and an archive
 * policy. None of those belong in a command-line surface shared with all the others.
 *
 * <p>So a source is described in a file, the file names its transport, and the connector registry
 * resolves it. Adding a transport becomes shipping a jar and writing a YAML file -- which is what
 * §4.3 promised when it made connectors a service-loader SPI.
 *
 * <p><strong>Transport only.</strong> Same boundary {@link ConnectorConfig} draws: no field
 * mappings, no canonical types. A source definition and a mapping are separate artifacts because
 * the same mapping has to survive the source moving from a nightly file drop to a live topic.
 *
 * @param sourceId the configured source, stable across connector instances and restarts
 * @param connectorInstanceId which instance this is
 * @param type the transport, resolved against the connector registry
 * @param settings connector-specific transport settings, interpreted by the connector
 * @param freshnessSla how stale this source may get before it is a {@code PipelineLag} (§4.7),
 *     or {@code null} where none is declared
 */
public record SourceDefinition(
        String sourceId,
        String connectorInstanceId,
        ConnectorType type,
        Map<String, String> settings,
        Duration freshnessSla) {

    private static final Set<String> ROOT_KEYS =
            Set.of("sourceId", "connectorInstanceId", "type", "settings", "freshnessSla");

    public SourceDefinition {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(connectorInstanceId, "connectorInstanceId");
        Objects.requireNonNull(type, "type");
        settings = Map.copyOf(settings);
    }

    /** The transport configuration this definition describes. */
    public ConnectorConfig toConnectorConfig() {
        return new ConnectorConfig(sourceId, connectorInstanceId, type, settings, freshnessSla);
    }

    public Optional<Duration> declaredFreshnessSla() {
        return Optional.ofNullable(freshnessSla);
    }

    /**
     * Reads a source definition from a YAML file.
     *
     * <p>Strict about unrecognised keys, for the reason every loader here is (ADR 0010): a
     * misspelled {@code retention} that is silently ignored is how a topic carrying non-retainable
     * responses gets landed anyway.
     *
     * @throws SourceDefinitionException naming every problem at once
     */
    public static SourceDefinition load(Path file) {
        List<String> problems = new ArrayList<>();
        Object parsed;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            parsed = new Yaml(new SafeConstructor(options))
                    .load(java.nio.file.Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new SourceDefinitionException(file, List.of("cannot be read: " + e.getMessage()));
        } catch (RuntimeException e) {
            throw new SourceDefinitionException(file, List.of("is not valid YAML: " + e.getMessage()));
        }

        if (!(parsed instanceof Map<?, ?> document)) {
            throw new SourceDefinitionException(file, List.of("must be a YAML mapping at the top level"));
        }

        Set<String> unknown = new LinkedHashSet<>();
        Map<String, Object> root = new LinkedHashMap<>();
        document.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (ROOT_KEYS.contains(name)) {
                root.put(name, value);
            } else {
                unknown.add(name);
            }
        });
        unknown.forEach(key -> problems.add(
                "unrecognised key '" + key + "'; a source definition accepts "
                        + ROOT_KEYS.stream().sorted().toList()));

        String sourceId = requiredText(root, "sourceId", problems);
        String instanceId = requiredText(root, "connectorInstanceId", problems);
        String typeId = requiredText(root, "type", problems);

        ConnectorType connectorType = null;
        if (typeId != null) {
            try {
                connectorType = ConnectorType.of(typeId);
            } catch (IllegalArgumentException e) {
                problems.add("'type': " + e.getMessage());
            }
        }

        Duration sla = null;
        Object declaredSla = root.get("freshnessSla");
        if (declaredSla != null) {
            try {
                sla = Duration.parse(String.valueOf(declaredSla));
            } catch (DateTimeParseException e) {
                problems.add("'freshnessSla' must be an ISO-8601 duration such as PT15M, found '"
                        + declaredSla + "'");
            }
        }

        Map<String, String> settings = new LinkedHashMap<>();
        Object declaredSettings = root.get("settings");
        if (declaredSettings instanceof Map<?, ?> settingsMap) {
            settingsMap.forEach((key, value) -> {
                if (value instanceof Map || value instanceof List) {
                    problems.add("setting '" + key + "' must be a scalar; a connector reads its "
                            + "settings as text");
                } else if (value != null) {
                    settings.put(String.valueOf(key), String.valueOf(value));
                }
            });
        } else if (declaredSettings != null) {
            problems.add("'settings' must be a mapping of setting names to values");
        }

        if (!problems.isEmpty()) {
            throw new SourceDefinitionException(file, problems);
        }
        return new SourceDefinition(sourceId, instanceId, connectorType, settings, sla);
    }

    /**
     * Every source definition a module ships, from its {@code sources/} directory.
     *
     * <p>Sorted by file name so a catalogue lists a source's arrivals in the same order every time.
     * A module with no {@code sources/} directory has none, which is not an error: transport
     * configuration may equally live outside the module, in a deployment's own repository.
     *
     * @throws SourceDefinitionException if any definition present cannot be read
     */
    public static List<SourceDefinition> loadDirectory(Path directory) {
        if (!java.nio.file.Files.isDirectory(directory)) {
            return List.of();
        }
        List<Path> files;
        try (var stream = java.nio.file.Files.list(directory)) {
            files = stream.filter(java.nio.file.Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .sorted()
                    .toList();
        } catch (java.io.IOException e) {
            throw new SourceDefinitionException(directory, List.of("cannot be listed: " + e.getMessage()));
        }
        return files.stream().map(SourceDefinition::load).toList();
    }

    /** Those of them that describe a given source. A source may arrive by more than one transport. */
    public static List<SourceDefinition> forSource(List<SourceDefinition> definitions, String sourceId) {
        return definitions.stream().filter(definition -> definition.sourceId().equals(sourceId)).toList();
    }

    private static String requiredText(Map<String, Object> root, String key, List<String> problems) {
        Object value = root.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            problems.add("'" + key + "' is required");
            return null;
        }
        return String.valueOf(value).trim();
    }

    /**
     * Resolves this definition's connector and configures it.
     *
     * @throws SourceDefinitionException if this deployment has no connector for the transport,
     *     listing what it does have -- an air-gapped operator needs to know whether they are
     *     missing a jar or have misspelled a transport
     */
    public SourceConnector connectorFrom(ConnectorRegistry registry) {
        SourceConnector connector = registry.forType(type).orElseThrow(() ->
                new SourceDefinitionException(null, List.of(
                        "no connector for transport '" + type + "' is on the classpath. This "
                                + "deployment can read: " + registry.availableTypes())));
        connector.configure(toConnectorConfig());
        return connector;
    }

    /** Keys only -- never values. A settings map routinely holds a credential. See ADR 0015. */
    @Override
    public String toString() {
        return "SourceDefinition[sourceId=" + sourceId + ", instance=" + connectorInstanceId
                + ", type=" + type + ", settingKeys=" + settings.keySet() + "]";
    }
}
