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
     * How records arrive from this source (ADR 0027).
     *
     * <p>Declared rather than inferred, so a source's shape is a fact the catalogue and the operator
     * can read rather than something discovered by watching it behave.
     */
    InteractionMode interactionMode();

    /**
     * Whether records from this source may be kept (ADR 0027).
     *
     * <p>Required, with no default. A default would be {@code RETAINED}, and a connector whose author
     * did not think about retention would silently land data that may not lawfully be kept. Making
     * every connector state it is the point: this is a legal question, and the person writing the
     * adapter is the one who knows the answer.
     */
    RetentionPosture retention();

    /**
     * Offers the connector somewhere durable to keep its read position (ADR 0030).
     *
     * <p>Called before {@link #configure}, always, and with
     * {@link SourceCheckpointStore#unavailable()} where a deployment has configured none. A
     * connector that needs a position therefore learns at configuration time that it has nowhere to
     * put one, via {@link SourceCheckpointStore#requireUsable}, rather than discovering it after a
     * landing run by re-reading its whole source.
     *
     * <p>A no-op by default, because for most transports it genuinely is one. A Kafka consumer
     * group is a position held on the broker and a file drop deliberately has none; only a
     * transport the platform must remember on behalf of -- an SFTP pull, a change feed -- overrides
     * this.
     */
    default void useCheckpointStore(SourceCheckpointStore store) {
        // This transport's source remembers for itself, or has nothing to remember.
    }

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
