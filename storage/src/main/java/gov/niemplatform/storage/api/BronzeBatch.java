package gov.niemplatform.storage.api;

import java.util.List;
import java.util.Objects;

/**
 * A set of envelopes landed together, committed as a unit.
 *
 * <p>Batching is what makes a landing atomic. A batch is visible once its manifest is written, so
 * a crash mid-write leaves an orphaned data file that no reader will ever see rather than a
 * partially readable batch -- which matters because silver must be rebuildable from bronze, and
 * a half-landed batch would rebuild differently each time.
 *
 * @param sourceId configured source every envelope in the batch came from
 * @param connectorInstanceId connector instance that produced it
 * @param envelopes the landed records, in source order
 */
public record BronzeBatch(String sourceId, String connectorInstanceId, List<RawEnvelope> envelopes) {

    public BronzeBatch {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(connectorInstanceId, "connectorInstanceId");
        envelopes = List.copyOf(envelopes);
        if (envelopes.isEmpty()) {
            throw new IllegalArgumentException("A bronze batch must contain at least one envelope");
        }
        for (RawEnvelope envelope : envelopes) {
            if (!envelope.sourceId().equals(sourceId)) {
                throw new IllegalArgumentException(
                        "Envelope " + envelope.envelopeId() + " belongs to source '" + envelope.sourceId()
                                + "', not '" + sourceId + "'");
            }
        }
    }

    public int size() {
        return envelopes.size();
    }

    /** Lowest offset in the batch, as ordered by the connector. */
    public SourceOffset firstOffset() {
        return envelopes.getFirst().offset();
    }

    /** Highest offset in the batch. */
    public SourceOffset lastOffset() {
        return envelopes.getLast().offset();
    }

    @Override
    public String toString() {
        return "BronzeBatch[source=%s, records=%d, offsets=%s..%s]"
                .formatted(sourceId, envelopes.size(), firstOffset(), lastOffset());
    }
}
