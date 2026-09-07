package gov.niemplatform.storage.api;

import java.time.Instant;
import java.util.Objects;

/**
 * One point in a canonical type's history.
 *
 * <p>Carries a wall-clock time because history is for humans and auditors, not for replay
 * comparison. Nothing in the replay path compares timestamps -- acceptance criterion 6 compares
 * record contents, which are deterministic, and a timestamp never would be.
 *
 * @param snapshotId identifier to read the type as it stood at this point
 * @param committedAt when the commit landed
 * @param recordCount total records visible at this snapshot, not the number added by it
 */
public record SilverSnapshot(long snapshotId, Instant committedAt, long recordCount) {

    public SilverSnapshot {
        Objects.requireNonNull(committedAt, "committedAt");
    }
}
