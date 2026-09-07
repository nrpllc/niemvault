package gov.niemplatform.contracts;

import gov.niemplatform.canonical.meta.CanonicalRoleDescriptor;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.canonical.meta.FieldType;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The shape a contract expects or emits (spec §4.2).
 *
 * <p>Schemas describe both source-shaped and canonical records. That is deliberate: the first
 * hop of a mapping DAG consumes a source shape and the last emits a canonical one, and a single
 * schema type means one validator, one violation format, and one quarantine path for both.
 *
 * <p><strong>Unexpected fields are a violation by default.</strong> A source that starts sending
 * a new column is exactly the quiet change spec §4.2 is written to catch — it breaks nothing,
 * passes every other check, and means the source changed underneath the mapping. Contracts may
 * opt out per schema where a source genuinely carries fields the platform does not care about.
 *
 * @param id stable identifier, e.g. {@code source:cad-csv/person} or a canonical qualified name
 * @param version content version of this schema
 * @param fields expected fields, in declaration order
 * @param allowUnexpectedFields whether fields absent from {@code fields} are tolerated
 */
public record Schema(
        String id,
        String version,
        List<FieldExpectation> fields,
        boolean allowUnexpectedFields,
        /**
         * What the source means by each field, where the contract's author wrote it down.
         *
         * <p>A side table rather than a component of {@link FieldExpectation}: validation never
         * reads it, and putting it on the expectation would make every construction of one carry
         * documentation that nothing at run time consults. It is catalogue material (§4.8,
         * ADR 0019) and belongs to the schema, not to the check.
         */
        Map<String, String> fieldDocs) implements Serializable {

    /** A schema with no documented fields. */
    public Schema(String id, String version, List<FieldExpectation> fields,
            boolean allowUnexpectedFields) {
        this(id, version, fields, allowUnexpectedFields, Map.of());
    }

    public Schema {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        fieldDocs = fieldDocs == null ? Map.of() : Map.copyOf(fieldDocs);
        fields = List.copyOf(fields);

        Map<String, FieldExpectation> byName = new LinkedHashMap<>();
        for (FieldExpectation field : fields) {
            if (byName.put(field.name(), field) != null) {
                throw new IllegalArgumentException(
                        "Schema '" + id + "' declares field '" + field.name() + "' more than once");
            }
        }
    }

    /** A strict schema: any field not declared here is a violation. */
    public static Schema strict(String id, String version, List<FieldExpectation> fields) {
        return new Schema(id, version, fields, false);
    }

    /**
     * Derives a schema from a canonical type, so canonical contracts cannot drift from the model.
     *
     * <p>The platform-assigned {@code canonicalId} and every association role are included, since
     * a canonical record carries all three and a schema that omitted them would reject valid
     * output. Roles are expected as references to their declared target type.
     */
    public static Schema ofCanonical(CanonicalTypeDescriptor descriptor) {
        List<FieldExpectation> expectations = new ArrayList<>();
        expectations.add(new FieldExpectation(
                CanonicalTypeDescriptor.CANONICAL_ID_FIELD, FieldType.IDENTITY, true, false,
                List.of(), null, null));

        for (CanonicalRoleDescriptor role : descriptor.roles()) {
            expectations.add(new FieldExpectation(
                    role.name(), FieldType.REF, true, false, List.of(), role.targetType(), null));
        }
        descriptor.fields().forEach(field -> expectations.add(FieldExpectation.ofCanonical(field)));

        return new Schema(descriptor.qualifiedName(), descriptor.version(), expectations, false);
    }

    /** What the source means by a field, if anyone said. */
    public Optional<String> docFor(String field) {
        return Optional.ofNullable(fieldDocs.get(field));
    }

    public Optional<FieldExpectation> field(String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst();
    }

    /** Field names this schema declares, in order. */
    public List<String> fieldNames() {
        return fields.stream().map(FieldExpectation::name).toList();
    }

    /** Returns a copy that tolerates fields it does not declare. */
    public Schema tolerantOfUnexpectedFields() {
        return new Schema(id, version, fields, true);
    }

    @Override
    public String toString() {
        return "Schema[" + id + "@" + version + ", " + fields.size() + " field(s)"
                + (allowUnexpectedFields ? ", tolerant" : ", strict") + "]";
    }
}
