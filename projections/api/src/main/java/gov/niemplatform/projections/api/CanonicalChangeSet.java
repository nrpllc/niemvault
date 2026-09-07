package gov.niemplatform.projections.api;

import java.util.List;
import java.util.Objects;

/**
 * An incremental change to canonical silver, for a projection to apply (spec §4.6).
 *
 * <p>Upserts only. Canonical records are identified by a platform-assigned identity, and a mapping
 * run either produces a record for an identity or does not -- there is no delete in the bronze to
 * silver path, because bronze is append-only and nothing in it is ever retracted.
 *
 * <p>Applying a change set must be idempotent. Replay re-applies the same changes by design
 * (criterion 6), and a projection that double-counted would make a correct replay produce a wrong
 * graph.
 *
 * @param runId the pipeline run that produced these changes, carried for lineage and events
 * @param changes records grouped by canonical type
 */
public record CanonicalChangeSet(String runId, List<TypedRecords> changes) {

    public CanonicalChangeSet {
        Objects.requireNonNull(runId, "runId");
        changes = List.copyOf(changes);
    }

    /** Total records across every type. */
    public int size() {
        return changes.stream().mapToInt(TypedRecords::size).sum();
    }

    public boolean isEmpty() {
        return size() == 0;
    }
}
