package gov.niemplatform.observability;

import java.io.Serializable;
import java.util.Objects;

/**
 * A redacted description of a value: its type, its size, and its character shape -- never the
 * value itself.
 *
 * <p>Spec §4.7 requires a contract violation to carry a sample, and §4.2 names the failure that
 * matters: a source quietly changing format while nothing errors. Diagnosing that needs the
 * value's <em>shape</em>, not its content. {@code "123-45-6789"} and {@code "123456789"} are
 * distinguishable as {@code ###-##-####} and {@code #########} without either SSN reaching a
 * log aggregator.
 *
 * <p>Digits collapse to {@code #}, letters to {@code A}, whitespace to {@code _}; everything
 * else is kept literally because punctuation is usually the thing that changed. Long values are
 * truncated, since a format change shows up in the first characters.
 *
 * <p>This is the only sanctioned way for record values to reach an event. See ADR 0015.
 */
public record ValueShape(String javaType, Integer length, String pattern) implements Serializable {

    /** Beyond this, a pattern describes the format without needing the whole value. */
    private static final int MAX_PATTERN_LENGTH = 32;

    private static final ValueShape ABSENT = new ValueShape("absent", null, null);

    public ValueShape {
        Objects.requireNonNull(javaType, "javaType");
    }

    /** Describes a value without disclosing it. */
    public static ValueShape of(Object value) {
        if (value == null) {
            return ABSENT;
        }
        if (value instanceof CharSequence text) {
            return new ValueShape("String", text.length(), pattern(text));
        }
        if (value instanceof java.util.Collection<?> collection) {
            return new ValueShape(value.getClass().getSimpleName(), collection.size(), null);
        }
        // Non-string scalars -- dates, numbers, booleans -- disclose nothing useful as a
        // pattern, and their type alone is what a shape violation turns on.
        return new ValueShape(value.getClass().getSimpleName(), null, null);
    }

    /** The shape of a value that was not present at all. */
    public static ValueShape absent() {
        return ABSENT;
    }

    private static String pattern(CharSequence text) {
        int limit = Math.min(text.length(), MAX_PATTERN_LENGTH);
        StringBuilder shape = new StringBuilder(limit + 1);
        for (int i = 0; i < limit; i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c)) {
                shape.append('#');
            } else if (Character.isLetter(c)) {
                shape.append('A');
            } else if (Character.isWhitespace(c)) {
                shape.append('_');
            } else {
                shape.append(c);
            }
        }
        if (text.length() > limit) {
            shape.append('…');
        }
        return shape.toString();
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(javaType);
        if (length != null) {
            out.append("(len=").append(length);
            if (pattern != null) {
                out.append(", shape=").append(pattern);
            }
            out.append(')');
        }
        return out.toString();
    }
}
