package gov.niemplatform.canonical.data;

/**
 * A record value was not of the type the canonical model declares.
 *
 * <p>Spec §9: no exception carries a bare string as its only payload. This one is structured
 * because it is a platform defect signal, not a data-quality signal -- bad source data is
 * caught by contract validation (§4.2) and quarantined long before materialisation. If this
 * throws, a hop or a codegen path is wrong.
 */
public class RecordTypeMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String recordTypeName;
    private final String fieldName;
    private final transient Class<?> expectedType;
    private final transient Class<?> actualType;

    public RecordTypeMismatchException(
            String recordTypeName, String fieldName, Class<?> expectedType, Class<?> actualType) {
        super("Record '%s' field '%s': expected %s, found %s"
                .formatted(recordTypeName, fieldName, expectedType.getName(), actualType.getName()));
        this.recordTypeName = recordTypeName;
        this.fieldName = fieldName;
        this.expectedType = expectedType;
        this.actualType = actualType;
    }

    public String recordTypeName() {
        return recordTypeName;
    }

    public String fieldName() {
        return fieldName;
    }

    public Class<?> expectedType() {
        return expectedType;
    }

    public Class<?> actualType() {
        return actualType;
    }
}
