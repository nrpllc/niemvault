package gov.niemplatform.storage.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Proof that a batch landed, and the handle for replaying it.
 *
 * @param batchId identifier assigned at landing, ordered so batches sort by landing sequence
 * @param sourceId source the batch came from
 * @param connectorInstanceId connector instance that landed it
 * @param recordCount how many envelopes it holds
 * @param landedAt when the batch was committed
 * @param firstOffset lowest source offset in the batch
 * @param lastOffset highest source offset in the batch
 * @param dataFile storage-relative path of the Parquet file
 * @param manifestFile storage-relative path of the sidecar JSON manifest
 */
public record BronzeBatchReceipt(
        String batchId,
        String sourceId,
        String connectorInstanceId,
        int recordCount,
        Instant landedAt,
        SourceOffset firstOffset,
        SourceOffset lastOffset,
        String dataFile,
        String manifestFile) {

    public BronzeBatchReceipt {
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(landedAt, "landedAt");
        if (recordCount <= 0) {
            throw new IllegalArgumentException("A landed batch must hold at least one record");
        }
    }
}
