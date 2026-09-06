package gov.niemplatform.contracts;

import gov.niemplatform.canonical.meta.CanonicalFieldDescriptor;
import gov.niemplatform.canonical.meta.FieldType;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What a contract expects of one field.
 *
 * <p>Two things beyond the canonical model's own field metadata matter here, and both exist for
 * source-shaped records rather than canonical ones:
 *
 * <ul>
 *   <li>{@code pattern} — a source field arrives as text long before it is a typed value, and a
 *       regex over that text is what catches a date format changing from {@code 2026-03-04} to
 *       {@code 03/04/2026} while both remain perfectly valid strings.
 *   <li>{@code refType} — a reference must point at the canonical type the contract says it does,
 *       or the graph projection writes an edge to the wrong kind of node.
 * </ul>
 *
 * @param name field name as it appears on the record
 * @param type expected value space
 * @param required whether absence is a violation
 * @param repeated whether the value must be a list of {@code type}
 * @param codeList permitted values for a {@link FieldType#CODE} field; empty means unconstrained
 * @param refType target canonical type for a {@link FieldType#REF} field, or {@code null}
 * @param pattern regex the textual form must match, or {@code null} for no constraint
 */
public record FieldExpectation(
        String name,
        FieldType type,
        boolean required,
        boolean repeated,
        List<String> codeList,
        String refType,
        String pattern) implements Serializable {

    public FieldExpectation {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        codeList = codeList == null ? List.of() : List.copyOf(codeList);
        if (pattern != null) {
            // Fail at construction, not on the first record. A bad regex in a contract artifact
            // is a deployment problem, and spec §9 wants those loud and early.
            Pattern.compile(pattern);
        }
    }

    /** An optional field of the given type with no further constraint. */
    public static FieldExpectation optional(String name, FieldType type) {
        return new FieldExpectation(name, type, false, false, List.of(), null, null);
    }

    /** A required field of the given type with no further constraint. */
    public static FieldExpectation required(String name, FieldType type) {
        return new FieldExpectation(name, type, true, false, List.of(), null, null);
    }

    /**
     * Derives an expectation from a canonical field, so a canonical schema never drifts from the
     * model it claims to describe.
     */
    public static FieldExpectation ofCanonical(CanonicalFieldDescriptor field) {
        return new FieldExpectation(
                field.name(),
                field.type(),
                field.required(),
                field.repeated(),
                field.codeList(),
                field.refType(),
                null);
    }

    /** Returns a copy carrying a textual-form constraint. */
    public FieldExpectation withPattern(String regex) {
        return new FieldExpectation(name, type, required, repeated, codeList, refType, regex);
    }

    /** Returns a copy with the required flag set as given. */
    public FieldExpectation withRequired(boolean isRequired) {
        return new FieldExpectation(name, type, isRequired, repeated, codeList, refType, pattern);
    }

    /** Compiled form of {@link #pattern()}, or {@code null} when unconstrained. */
    public Pattern compiledPattern() {
        return pattern == null ? null : Pattern.compile(pattern);
    }
}
