package gov.niemplatform.canonical.meta;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The value space of a canonical field.
 *
 * <p>Deliberately small. The canonical model exists to be a stable interchange core, and every
 * additional primitive is another shape every connector, contract, and projection must handle.
 * Richer semantics belong in {@link #CODE} code lists and in contract rules, not in new types.
 */
public enum FieldType {
    STRING(String.class),
    /** A value constrained to a declared code list. Carried as a string; constrained by contract. */
    CODE(String.class),
    DATE(LocalDate.class),
    DATE_TIME(Instant.class),
    INTEGER(Long.class),
    DECIMAL(BigDecimal.class),
    BOOLEAN(Boolean.class),
    /** A reference to another canonical entity by its platform-assigned identity. */
    REF(CanonicalRef.class);

    private final Class<?> javaType;

    FieldType(Class<?> javaType) {
        this.javaType = javaType;
    }

    /** The Java type a value of this field type must have. */
    public Class<?> javaType() {
        return javaType;
    }
}
