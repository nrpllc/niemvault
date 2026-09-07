package gov.niemplatform.storage.api;

import java.util.Objects;

/**
 * A commit that made records visible in silver.
 *
 * <p>The snapshot identifier is the handle a replay comparison uses: silver before a run and
 * silver after it are both addressable, so what the run changed is checkable rather than assumed.
 *
 * @param typeName canonical type committed to
 * @param snapshotId identifier of the resulting snapshot
 * @param recordCount records the commit added
 */
public record SilverCommit(String typeName, long snapshotId, int recordCount) {

    public SilverCommit {
        Objects.requireNonNull(typeName, "typeName");
        if (recordCount < 0) {
            throw new IllegalArgumentException("A commit cannot add a negative number of records");
        }
    }
}
