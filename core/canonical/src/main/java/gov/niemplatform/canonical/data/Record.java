package gov.niemplatform.canonical.data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The generic, immutable record that flows through the mapping DAG.
 *
 * <p>Every hop consumes and produces one of these (spec §4.2). Source-shaped records and
 * canonical records share the type so a single validator, a single quarantine path, and a
 * single lineage emitter serve every hop regardless of where in the DAG it sits.
 *
 * <p><strong>Value semantics.</strong> Equality is over the type name and the field values,
 * independent of field ordering. That is what lets acceptance criterion 7 compare batch output
 * against streaming output directly, and criterion 6 compare replayed silver against original
 * silver, without either comparison depending on operator scheduling order.
 *
 * <p><strong>Values must be immutable and serializable.</strong> Records cross Flink operator
 * boundaries and are held in state; a mutable value would break both determinism and replay.
 */
public final class Record implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String typeName;
    private final LinkedHashMap<String, Object> values;

    private Record(String typeName, LinkedHashMap<String, Object> values) {
        this.typeName = typeName;
        this.values = values;
    }

    public static Builder builder(String typeName) {
        return new Builder(typeName);
    }

    /**
     * Identifier for the shape of this record: {@code namespace#Name} for canonical records,
     * or a source shape identifier such as {@code source:cad-csv/incident} before canonicalisation.
     */
    public String typeName() {
        return typeName;
    }

    /** Whether the record carries a value -- including an explicit null -- under this name. */
    public boolean has(String name) {
        return values.containsKey(name);
    }

    /** Whether the record carries a non-null value under this name. */
    public boolean hasValue(String name) {
        return values.get(name) != null;
    }

    /** Raw untyped access, for the validator and for diagnostics. */
    public Object raw(String name) {
        return values.get(name);
    }

    /**
     * Typed access. A mismatch is a platform bug rather than bad source data -- contract
     * validation runs before materialisation -- so it throws rather than returning empty.
     *
     * @throws RecordTypeMismatchException if the value is present but not of the expected type
     */
    public <T> T get(String name, Class<T> type) {
        Object value = values.get(name);
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new RecordTypeMismatchException(typeName, name, type, value.getClass());
        }
        return type.cast(value);
    }

    /**
     * Typed access to a repeated field. Returns an empty list when absent, so callers need not
     * distinguish "no value" from "empty list" -- a distinction the canonical model does not make.
     *
     * @throws RecordTypeMismatchException if the value is not a list, or any element is of the
     *     wrong type
     */
    public <T> List<T> getList(String name, Class<T> elementType) {
        Object value = values.get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new RecordTypeMismatchException(typeName, name, List.class, value.getClass());
        }
        List<T> typed = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element != null && !elementType.isInstance(element)) {
                throw new RecordTypeMismatchException(
                        typeName, name + "[]", elementType, element.getClass());
            }
            typed.add(elementType.cast(element));
        }
        return List.copyOf(typed);
    }

    /** All values, in insertion order. Unmodifiable. */
    public Map<String, Object> values() {
        return Collections.unmodifiableMap(values);
    }

    /** Field names present on this record, in insertion order. */
    public List<String> fieldNames() {
        return List.copyOf(values.keySet());
    }

    /** Returns a copy carrying a different type name, e.g. after a canonicalising hop. */
    public Record withTypeName(String newTypeName) {
        return new Record(Objects.requireNonNull(newTypeName, "typeName"), new LinkedHashMap<>(values));
    }

    /** Returns a builder pre-populated from this record, for hops that add or replace fields. */
    public Builder toBuilder() {
        Builder builder = new Builder(typeName);
        builder.values.putAll(values);
        return builder;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Record record
                && typeName.equals(record.typeName)
                && values.equals(record.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(typeName, values);
    }

    /**
     * Field names only -- never values.
     *
     * <p>Records routinely carry criminal justice PII. An accidental {@code log.debug(record)}
     * must not become a disclosure, so values are available only through
     * {@link #values()} and the explicitly redacting sampler used by contract violation events
     * (spec §4.7).
     */
    @Override
    public String toString() {
        return "Record[type=" + typeName + ", fields=" + values.keySet() + "]";
    }

    /** Accumulates values for a new {@link Record}. Not thread-safe; use one per record. */
    public static final class Builder {

        private final String typeName;
        private final LinkedHashMap<String, Object> values = new LinkedHashMap<>();

        private Builder(String typeName) {
            this.typeName = Objects.requireNonNull(typeName, "typeName");
        }

        /** Sets a value. A null value is recorded as an explicitly present absence. */
        public Builder set(String name, Object value) {
            values.put(Objects.requireNonNull(name, "name"), value);
            return this;
        }

        /** Sets a value only when non-null, leaving the field absent otherwise. */
        public Builder setIfPresent(String name, Object value) {
            if (value != null) {
                values.put(Objects.requireNonNull(name, "name"), value);
            }
            return this;
        }

        /** Removes a field entirely, e.g. a source column a hop is contracted to drop. */
        public Builder remove(String name) {
            values.remove(name);
            return this;
        }

        public Record build() {
            return new Record(typeName, new LinkedHashMap<>(values));
        }
    }
}
