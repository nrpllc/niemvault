package gov.niemplatform.storage.api;

import java.util.Objects;
import java.util.Optional;

/**
 * A selection of bronze to read back, for replay (spec §5).
 *
 * <p>Replay is defined as "given a bronze range and a mapping version, rebuild silver
 * deterministically", so this is half of that definition. Bounds are inclusive and expressed as
 * batch identifiers rather than timestamps: batch identifiers are exact, whereas a timestamp
 * range would silently include or exclude a batch depending on clock skew, and a replay that is
 * not byte-identical is not a replay.
 *
 * @param fromBatchId first batch to include, or {@code null} for the earliest
 * @param toBatchId last batch to include, or {@code null} for the latest
 */
public record BronzeRange(String fromBatchId, String toBatchId) {

    private static final BronzeRange ALL = new BronzeRange(null, null);

    /** Every batch landed for the source. */
    public static BronzeRange all() {
        return ALL;
    }

    /** A single batch. */
    public static BronzeRange batch(String batchId) {
        Objects.requireNonNull(batchId, "batchId");
        return new BronzeRange(batchId, batchId);
    }

    /** An inclusive span of batches. */
    public static BronzeRange between(String fromBatchId, String toBatchId) {
        return new BronzeRange(fromBatchId, toBatchId);
    }

    public Optional<String> from() {
        return Optional.ofNullable(fromBatchId);
    }

    public Optional<String> to() {
        return Optional.ofNullable(toBatchId);
    }

    /** Whether a batch identifier falls within this range. */
    public boolean includes(String batchId) {
        if (fromBatchId != null && batchId.compareTo(fromBatchId) < 0) {
            return false;
        }
        return toBatchId == null || batchId.compareTo(toBatchId) <= 0;
    }

    @Override
    public String toString() {
        return "BronzeRange[" + (fromBatchId == null ? "earliest" : fromBatchId)
                + ".." + (toBatchId == null ? "latest" : toBatchId) + "]";
    }
}
