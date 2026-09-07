package gov.niemplatform.projections.api;

import java.util.List;
import java.util.Objects;

/**
 * The complete contents of canonical silver, for a projection to rebuild from (spec §4.6).
 *
 * <p>Distinct from {@link CanonicalChangeSet} because the two mean different things and a
 * projection must treat them differently: a change set adds to what is there, a snapshot
 * <em>replaces</em> it. Spec §4.6 requires every projection to support full rebuild from silver,
 * and a rebuild that merely merged on top of stale contents would leave records that silver no
 * longer contains.
 *
 * @param contents every record in silver, grouped by canonical type
 */
public record CanonicalSnapshot(List<TypedRecords> contents) {

    public CanonicalSnapshot {
        contents = List.copyOf(contents);
    }

    public int size() {
        return contents.stream().mapToInt(TypedRecords::size).sum();
    }
}
