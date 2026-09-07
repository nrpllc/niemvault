package gov.niemplatform.runtime.transforms;

import gov.niemplatform.canonical.data.Record;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a transform can read: the record arriving at the hop, and whatever earlier steps in the
 * same hop have already emitted.
 *
 * <p>Letting a step read earlier output is what allows a mapping to normalise once and reuse the
 * result -- split a packed name field, then upper-case the surname -- rather than repeating the
 * split in every dependent step.
 */
public final class TransformInput implements Serializable {

    private static final long serialVersionUID = 1L;

    private final Record source;
    private final Map<String, Object> emitted;

    public TransformInput(Record source, Map<String, Object> emitted) {
        this.source = Objects.requireNonNull(source, "source");
        this.emitted = new LinkedHashMap<>(Objects.requireNonNull(emitted, "emitted"));
    }

    /** The record as it arrived at this hop. */
    public Record source() {
        return source;
    }

    /** A field from the arriving record. */
    public Object sourceValue(String field) {
        return source.raw(field);
    }

    /** A field from the arriving record as text, if it is text and non-blank. */
    public Optional<String> sourceText(String field) {
        Object value = source.raw(field);
        if (value == null) {
            return Optional.empty();
        }
        String text = value.toString();
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    /** A value emitted by an earlier step of this hop. */
    public Object emittedValue(String field) {
        return emitted.get(field);
    }

    /** An earlier step's output as text, if it is text and non-blank. */
    public Optional<String> emittedText(String field) {
        Object value = emitted.get(field);
        if (value == null) {
            return Optional.empty();
        }
        String text = value.toString();
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    /**
     * Reads a named field, preferring an earlier step's output over the arriving record.
     *
     * <p>Steps therefore refer to fields by name without caring whether the value came from the
     * source or from a previous step, which keeps mapping artifacts readable.
     */
    public Optional<String> text(String field) {
        Optional<String> fromSteps = emittedText(field);
        return fromSteps.isPresent() ? fromSteps : sourceText(field);
    }

    /** Untyped read with the same precedence as {@link #text}. */
    public Object value(String field) {
        return emitted.containsKey(field) ? emitted.get(field) : source.raw(field);
    }
}
