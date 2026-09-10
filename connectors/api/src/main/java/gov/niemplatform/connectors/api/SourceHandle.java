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

    /**
     * Confirms that everything yielded so far is durably in bronze.
     *
     * <p>Called by {@link LandingService} after each bronze batch commits, and never before. A
     * transport that holds a read position on the source side -- a Kafka consumer group offset, a
     * CDC log sequence number, an FTP archive move -- advances it here and nowhere else.
     *
     * <p>The ordering is the whole point, and it only goes one way. Advancing a source position
     * before the batch commits converts a crash into <em>lost records</em>: the source believes it
     * has delivered them and will not send them again, and bronze does not have them. Advancing it
     * after converts the same crash into <em>duplicate records</em>, which bronze can already
     * detect -- envelope identity is derived from source, offset, and content hash, so a
     * redelivered record lands with the identity it had the first time. One of those failures is
     * recoverable and the other is not.
     *
     * <p>A no-op by default, because for a source with no position to advance it genuinely is one:
     * a file drop re-reads a directory from the start whatever happened last time. A connector that
     * <em>does</em> hold a position and does not override this will re-deliver everything on every
     * open, which is visible and safe, rather than losing records, which is neither.
     */
    default void acknowledge() {
        // Nothing to advance. See the javadoc: silence here means "this source has no position",
        // never "acknowledgement was forgotten".
    }

    @Override
    void close();
}
