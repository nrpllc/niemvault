package gov.niemplatform.connectors.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Discovers connectors on the classpath (spec §4.3: SPI registration).
 *
 * <p>Service-loader based, so an agency ships a connector as a jar and the platform finds it --
 * no registration list to edit, no platform rebuild. That matters for the air-gapped delivery
 * mode (§6): adding a transport must not require a new platform release.
 *
 * <p>A duplicate {@link ConnectorType} across two jars is an error, not a last-one-wins. Which
 * implementation you got would otherwise depend on classpath order, and diagnosing that during
 * an incident is miserable.
 */
public final class ConnectorRegistry {

    private final Map<ConnectorType, SourceConnector> byType;

    private ConnectorRegistry(Map<ConnectorType, SourceConnector> byType) {
        this.byType = byType;
    }

    /** Loads every connector visible to the platform class loader. */
    public static ConnectorRegistry discover() {
        return discover(ServiceLoader.load(SourceConnector.class));
    }

    /** Loads from a given service loader, for tests and for isolated class loaders. */
    public static ConnectorRegistry discover(ServiceLoader<SourceConnector> loader) {
        Map<ConnectorType, SourceConnector> found = new LinkedHashMap<>();
        List<String> conflicts = new ArrayList<>();
        for (SourceConnector connector : loader) {
            SourceConnector previous = found.putIfAbsent(connector.type(), connector);
            if (previous != null) {
                conflicts.add("%s is provided by both %s and %s".formatted(
                        connector.type(), previous.getClass().getName(), connector.getClass().getName()));
            }
        }
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException(
                    "Conflicting connector registrations: " + String.join("; ", conflicts));
        }
        return new ConnectorRegistry(Map.copyOf(found));
    }

    /** Builds a registry from known instances, bypassing discovery. */
    public static ConnectorRegistry of(SourceConnector... connectors) {
        Map<ConnectorType, SourceConnector> found = new LinkedHashMap<>();
        for (SourceConnector connector : connectors) {
            found.put(connector.type(), connector);
        }
        return new ConnectorRegistry(Map.copyOf(found));
    }

    public Optional<SourceConnector> forType(ConnectorType type) {
        return Optional.ofNullable(byType.get(type));
    }

    /**
     * Every transport this deployment can read, by type id.
     *
     * <p>Sorted rather than left in discovery order, because discovery order is classpath order:
     * not something an operator chose, not stable across two installations of the same release, and
     * so useless in the two places this list is read -- {@code niem connectors}, and the message
     * telling an operator what they could have written instead of the transport they misspelled.
     */
    public List<ConnectorType> availableTypes() {
        return byType.keySet().stream()
                .sorted(Comparator.comparing(ConnectorType::id))
                .toList();
    }

    public boolean isEmpty() {
        return byType.isEmpty();
    }
}
