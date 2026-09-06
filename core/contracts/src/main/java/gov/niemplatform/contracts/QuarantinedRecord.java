package gov.niemplatform.contracts;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.observability.ContractViolation.Failure;
import gov.niemplatform.observability.Direction;
import gov.niemplatform.observability.PipelineContext;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * A record that failed its contract, held with everything needed to understand and replay it.
 *
 * <p>Quarantine is not a bin. Spec §4.2 is explicit that a violation must not silently drop the
 * record, and §4.4 requires silver to be rebuildable from bronze -- so a quarantined record must
 * retain enough context that, once the mapping or the contract is corrected, it can be
 * reprocessed rather than mourned.
 *
 * <p>Unlike an observability event, this <em>does</em> hold the record's real values. That is the
 * point of it: quarantine is the one place the offending data is kept, so it lives wherever
 * bronze lives and under the same access controls, not in a log aggregator. {@link #toString()}
 * still refuses to render values, for the same reason {@link Record#toString()} does.
 *
 * @param contractId contract that rejected it, name and version
 * @param hopId hop at which it was rejected
 * @param direction whether it failed on the way in or the way out
 * @param context run and source it came from
 * @param record the record itself, values intact
 * @param failures every expectation it failed
 */
public record QuarantinedRecord(
        ContractId contractId,
        String hopId,
        Direction direction,
        PipelineContext context,
        Record record,
        List<Failure> failures) implements Serializable {

    public QuarantinedRecord {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(hopId, "hopId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(record, "record");
        failures = List.copyOf(failures);
        if (failures.isEmpty()) {
            throw new IllegalArgumentException("A quarantined record must carry at least one failure");
        }
    }

    /**
     * Field names and failure summaries only -- never values.
     *
     * <p>See ADR 0015. The record's values are reachable through {@link #record()}, which is an
     * explicit call a reviewer can see.
     */
    @Override
    public String toString() {
        return "QuarantinedRecord[contract=%s, hop=%s, direction=%s, record=%s, failures=%d]"
                .formatted(contractId, hopId, direction, record, failures.size());
    }
}
