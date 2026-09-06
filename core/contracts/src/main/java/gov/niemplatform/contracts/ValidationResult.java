package gov.niemplatform.contracts;

import gov.niemplatform.observability.ContractViolation;
import java.io.Serializable;
import java.util.List;

/**
 * The outcome of validating one record against one side of a hop contract.
 *
 * <p>Carries every failure rather than the first. A record with three problems produces three
 * failures in one event, so an operator fixing a source mapping sees the whole picture instead
 * of discovering the next problem on the next run.
 *
 * <p>Failures reuse the observability failure type, so a violation converts to a
 * {@link ContractViolation} event without reshaping -- and inherits its redaction guarantee:
 * values reach a failure only as a shape.
 */
public record ValidationResult(List<ContractViolation.Failure> failures) implements Serializable {

    private static final ValidationResult VALID = new ValidationResult(List.of());

    public ValidationResult {
        failures = List.copyOf(failures);
    }

    /** The record met every expectation. */
    public static ValidationResult valid() {
        return VALID;
    }

    /** The record failed at least one expectation. */
    public static ValidationResult invalid(List<ContractViolation.Failure> failures) {
        if (failures.isEmpty()) {
            throw new IllegalArgumentException("An invalid result must carry at least one failure");
        }
        return new ValidationResult(failures);
    }

    public boolean isValid() {
        return failures.isEmpty();
    }

    @Override
    public String toString() {
        return isValid() ? "ValidationResult[valid]" : "ValidationResult" + failures;
    }
}
