package gov.niemplatform.projections.api;

import java.util.Objects;

/**
 * A projection could not be written or read.
 *
 * <p>Spec §9: structured, never a bare string. Fatal to a run, unlike a contract violation: gold
 * that silently diverges from silver is precisely the condition §4.7's {@code ProjectionDivergence}
 * exists to detect, and continuing would leave an investigator querying something wrong without
 * anything saying so.
 */
public class ProjectionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** What was being attempted. */
    public enum Operation {
        APPLY,
        REBUILD,
        COUNT,
        CONNECT
    }

    private final Operation operation;
    private final ProjectionType projectionType;
    private final String canonicalType;

    public ProjectionException(
            Operation operation, ProjectionType projectionType, String canonicalType,
            String detail, Throwable cause) {
        super("%s failed for the %s projection%s: %s".formatted(
                operation, projectionType,
                canonicalType == null ? "" : " of canonical type '" + canonicalType + "'",
                detail), cause);
        this.operation = Objects.requireNonNull(operation, "operation");
        this.projectionType = projectionType;
        this.canonicalType = canonicalType;
    }

    public Operation operation() {
        return operation;
    }

    public ProjectionType projectionType() {
        return projectionType;
    }

    public String canonicalType() {
        return canonicalType;
    }
}
