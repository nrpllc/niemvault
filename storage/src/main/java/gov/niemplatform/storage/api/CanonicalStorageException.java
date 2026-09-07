package gov.niemplatform.storage.api;

import java.util.Objects;

/**
 * Silver could not be written or read.
 *
 * <p>Spec §9: structured, never a bare string. Unlike a contract violation, a silver failure is
 * fatal to the run: a mapping that produced canonical records the platform then failed to store
 * leaves silver disagreeing with bronze, and the whole rebuild guarantee rests on them agreeing.
 */
public class CanonicalStorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** What was being attempted. */
    public enum Operation {
        CREATE,
        APPEND,
        READ,
        HISTORY,
        DROP,
        LIST
    }

    private final Operation operation;
    private final String typeName;

    public CanonicalStorageException(Operation operation, String typeName, String detail, Throwable cause) {
        super("%s failed for canonical type '%s': %s".formatted(operation, typeName, detail), cause);
        this.operation = Objects.requireNonNull(operation, "operation");
        this.typeName = typeName;
    }

    public CanonicalStorageException(Operation operation, String typeName, String detail) {
        this(operation, typeName, detail, null);
    }

    public Operation operation() {
        return operation;
    }

    public String typeName() {
        return typeName;
    }
}
