package gov.niemplatform.contracts;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.observability.ContractViolation;
import gov.niemplatform.observability.Direction;
import gov.niemplatform.observability.ObservabilityEmitter;
import gov.niemplatform.observability.PipelineContext;
import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

/**
 * Applies a hop contract to a record and does what spec §4.2 requires when it fails.
 *
 * <p>Three obligations, in this order:
 *
 * <ol>
 *   <li>route the record to quarantine, keeping its values, and take the identifier back;
 *   <li>emit a {@link ContractViolation} carrying that identifier, so the event is actionable;
 *   <li>withhold the record from the rest of the pipeline -- without halting the pipeline.
 * </ol>
 *
 * <p>The ordering matters. Quarantining first means the event can name where the record went; an
 * event that says a record was rejected but not where to find it sends an operator hunting.
 *
 * <p>A gate wraps <em>one direction of one hop</em>. Spec §4.2 requires both directions to be
 * validated, so the runtime places two gates per hop -- deliberately, rather than hiding it
 * inside a single call, because "did we validate the output too?" should be answerable by
 * looking at the job graph.
 */
public final class ContractGate implements Serializable {

    private static final long serialVersionUID = 1L;

    private final HopContract contract;
    private final Direction direction;
    private final QuarantineSink quarantine;
    private final ObservabilityEmitter emitter;

    public ContractGate(
            HopContract contract,
            Direction direction,
            QuarantineSink quarantine,
            ObservabilityEmitter emitter) {
        this.contract = Objects.requireNonNull(contract, "contract");
        this.direction = Objects.requireNonNull(direction, "direction");
        this.quarantine = Objects.requireNonNull(quarantine, "quarantine");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
    }

    /**
     * Validates a record, quarantining and reporting it if it fails.
     *
     * @param record the record entering or leaving the hop
     * @param runContext the run this record belongs to; narrowed to this hop internally
     * @return the record if it passed, or empty if it was quarantined
     */
    public Optional<Record> check(Record record, PipelineContext runContext) {
        ValidationResult result = contract.validate(record, direction);
        if (result.isValid()) {
            return Optional.of(record);
        }

        PipelineContext hopContext = runContext.withHop(contract.hopId(), contract.id().version());

        // A record that failed on the way in still has values worth keeping; a null record has
        // nothing to quarantine, so it is reported without a quarantine identifier.
        String quarantineId = null;
        if (record != null) {
            quarantineId = quarantine.quarantine(new QuarantinedRecord(
                    contract.id(), contract.hopId(), direction, hopContext, record, result.failures()));
        }

        emitter.emit(new ContractViolation(
                hopContext,
                direction,
                record == null ? contract.schemaFor(direction).id() : record.typeName(),
                result.failures(),
                quarantineId));

        return Optional.empty();
    }

    public HopContract contract() {
        return contract;
    }

    public Direction direction() {
        return direction;
    }

    @Override
    public String toString() {
        return "ContractGate[" + contract.id() + ", " + direction + "]";
    }
}
