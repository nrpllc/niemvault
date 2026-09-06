package gov.niemplatform.storage.api;

import java.util.Objects;

/**
 * Bronze could not store or retrieve a record.
 *
 * <p>Spec §9: no exception carries a bare string as its only payload. This one names the source,
 * the operation, and where in storage it was working, because a landing failure is an incident
 * -- a record the platform accepted but did not store defeats the whole audit and replay story.
 */
public class BronzeStorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** What was being attempted when it failed. */
    public enum Operation {
        APPEND,
        READ,
        LIST,
        INTEGRITY
    }

    private final Operation operation;
    private final String sourceId;
    private final String location;

    public BronzeStorageException(Operation operation, String sourceId, String location,
            String detail, Throwable cause) {
        super("%s failed for source '%s' at %s: %s".formatted(operation, sourceId, location, detail), cause);
        this.operation = Objects.requireNonNull(operation, "operation");
        this.sourceId = sourceId;
        this.location = location;
    }

    public BronzeStorageException(Operation operation, String sourceId, String location, String detail) {
        this(operation, sourceId, location, detail, null);
    }

    public Operation operation() {
        return operation;
    }

    public String sourceId() {
        return sourceId;
    }

    /** Storage location involved, e.g. a batch directory or file. */
    public String location() {
        return location;
    }
}
