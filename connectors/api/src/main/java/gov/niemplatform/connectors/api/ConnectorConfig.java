package gov.niemplatform.connectors.api;

import java.io.Serializable;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Transport configuration for one connector instance (spec §4.3).
 *
 * <p><strong>Transport only.</strong> Spec §4.3 splits source onboarding into two independent
 * halves -- transport configuration, which is boilerplate, and schema mapping, which is where the
 * value is -- and says the code must reflect that separation. So this type carries connection
 * settings and nothing else: no field mappings, no canonical types, no transformation. A
 * connector that needed to know the canonical model would have collapsed the two halves.
 *
 * <p>Settings are untyped strings by design. A connector the platform has never seen must be
 * configurable without changing this class, and each connector validates and interprets its own
 * keys through {@link #requireOnly}.
 *
 * @param sourceId the configured source, stable across connector instances and restarts
 * @param connectorInstanceId which instance this is, so two instances of one connector type are
 *     distinguishable in bronze and during an incident
 * @param type the transport
 * @param settings connector-specific transport settings
 * @param freshnessSla how stale this source may get before it is a {@code PipelineLag} (§4.7),
 *     or {@code null} where none is declared
 */
public record ConnectorConfig(
        String sourceId,
        String connectorInstanceId,
        ConnectorType type,
        Map<String, String> settings,
        Duration freshnessSla) implements Serializable {

    public ConnectorConfig {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(connectorInstanceId, "connectorInstanceId");
        Objects.requireNonNull(type, "type");
        settings = Map.copyOf(settings);
        if (sourceId.isBlank() || connectorInstanceId.isBlank()) {
            throw new IllegalArgumentException("A connector needs a source id and an instance id");
        }
    }

    public static ConnectorConfig of(
            String sourceId, String connectorInstanceId, ConnectorType type, Map<String, String> settings) {
        return new ConnectorConfig(sourceId, connectorInstanceId, type, settings, null);
    }

    /**
     * A setting that must be present.
     *
     * @throws ConnectorConfigurationException if absent or blank
     */
    public String requiredSetting(String key) {
        String value = settings.get(key);
        if (value == null || value.isBlank()) {
            throw new ConnectorConfigurationException(sourceId, connectorInstanceId,
                    java.util.List.of(new ConnectorConfigurationException.Problem(
                            key, "required setting is missing")));
        }
        return value.trim();
    }

    /** A setting that may be absent. */
    public Optional<String> setting(String key) {
        return Optional.ofNullable(settings.get(key)).map(String::trim).filter(value -> !value.isEmpty());
    }

    /** A setting with a fallback. */
    public String settingOr(String key, String fallback) {
        return setting(key).orElse(fallback);
    }

    /** An integer setting with a fallback, rejected rather than coerced if unparseable. */
    public int intSettingOr(String key, int fallback) {
        Optional<String> raw = setting(key);
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.get());
        } catch (NumberFormatException e) {
            throw new ConnectorConfigurationException(sourceId, connectorInstanceId,
                    java.util.List.of(new ConnectorConfigurationException.Problem(
                            key, "must be a whole number, found '" + raw.get() + "'")));
        }
    }

    /** A filesystem path setting. */
    public Path pathSetting(String key) {
        return Path.of(requiredSetting(key));
    }

    /**
     * Rejects any setting the connector does not recognise.
     *
     * <p>Spec §9 and ADR 0010: config is validated on load and fails loudly. A misspelled
     * {@code directroy} that is silently ignored leaves a connector reading from a default nobody
     * chose, which is worse than refusing to start.
     *
     * @throws ConnectorConfigurationException naming every unrecognised key at once
     */
    public void requireOnly(Set<String> recognisedKeys) {
        Set<String> unknown = new LinkedHashSet<>(settings.keySet());
        unknown.removeAll(recognisedKeys);
        if (!unknown.isEmpty()) {
            java.util.List<ConnectorConfigurationException.Problem> problems = unknown.stream()
                    .map(key -> new ConnectorConfigurationException.Problem(key,
                            "unrecognised setting; this connector accepts "
                                    + recognisedKeys.stream().sorted().toList()))
                    .toList();
            throw new ConnectorConfigurationException(sourceId, connectorInstanceId, problems);
        }
    }

    /** How stale this source may get before it is reported as lagging. */
    public Optional<Duration> declaredFreshnessSla() {
        return Optional.ofNullable(freshnessSla);
    }

    /**
     * Setting keys only -- never values.
     *
     * <p>Connector settings routinely hold credentials and connection strings. Same obligation as
     * {@code Record.toString()}; see ADR 0015.
     */
    @Override
    public String toString() {
        Map<String, Object> shown = new LinkedHashMap<>();
        shown.put("sourceId", sourceId);
        shown.put("instance", connectorInstanceId);
        shown.put("type", type.id());
        shown.put("settingKeys", settings.keySet());
        return "ConnectorConfig" + shown;
    }
}
