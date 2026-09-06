package gov.niemplatform.connectors.api;

/**
 * Reads a source and lands it (spec §4.3).
 *
 * <p>Onboarding a source has two independent halves, and this interface is deliberately only
 * half of it:
 *
 * <ol>
 *   <li><strong>Transport configuration</strong> -- boilerplate. Kafka, webhook, MQTT, ODBC, CDC,
 *       file drop. That is what a {@code SourceConnector} does.
 *   <li><strong>Schema mapping</strong> -- where the value is. Sample data in, proposed canonical
 *       mapping out, human review and approval. That happens nowhere in this package.
 * </ol>
 *
 * <p>A connector therefore never sees the canonical model, a mapping, or a contract. If one ever
 * needs to, the two halves have been collapsed and the mapping asset stops being reusable across
 * transports -- which is the thing that compounds.
 *
 * <p>Implementations are discovered through {@link java.util.ServiceLoader}, so an agency can
 * ship a connector for a transport the platform has never heard of.
 *
 * <p>Lifecycle: {@code configure} once, then {@code open} one or more times, then {@code close}.
 * {@code health} may be called at any point, including before {@code configure}.
 */
public interface SourceConnector extends AutoCloseable {

    /** The transport this connector speaks. */
    ConnectorType type();

    /**
     * Applies transport configuration.
     *
     * @throws ConnectorConfigurationException if the settings are unusable, naming every problem
     */
    void configure(ConnectorConfig config);

    /**
     * Opens a read against the source.
     *
     * <p>Every connector lands into bronze identically (spec §4.3), which is why this returns
     * envelopes rather than anything transport-shaped.
     */
    SourceHandle open();

    /** Whether the connector can currently reach its source. */
    HealthStatus health();

    @Override
    void close();
}
