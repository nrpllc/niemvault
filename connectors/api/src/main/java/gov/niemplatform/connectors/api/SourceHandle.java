package gov.niemplatform.connectors.api;

import gov.niemplatform.storage.api.RawEnvelope;
import java.util.stream.Stream;

/**
 * An open read against a source, producing envelopes (spec §4.3).
 *
 * <p>A stream rather than a callback or a batch, because the same abstraction has to serve both
 * execution modalities: a file drop yields a bounded stream, a Kafka connector an unbounded one,
 * and spec §5 requires one transformation definition to run over either without modification.
 *
 * <p>The stream holds transport resources and must be closed. Closing the handle closes the
 * stream.
 */
public interface SourceHandle extends AutoCloseable {

    /**
     * Envelopes from the source, in source order.
     *
     * <p>Every element is already at the landing boundary: transport differences are resolved
     * before an envelope exists, so nothing downstream can tell a file drop from a Kafka topic.
     */
    Stream<RawEnvelope> envelopes();

    @Override
    void close();
}
