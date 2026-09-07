package gov.niemplatform.storage.iceberg;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalFieldDescriptor;
import gov.niemplatform.canonical.meta.CanonicalId;
import gov.niemplatform.canonical.meta.CanonicalRef;
import gov.niemplatform.canonical.meta.CanonicalRoleDescriptor;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.iceberg.Schema;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

/**
 * Translates between the canonical model and Iceberg.
 *
 * <p>The Iceberg schema is <strong>derived from the canonical descriptor</strong>, never declared
 * alongside it. A hand-maintained table definition would drift from the model that writes to it,
 * and the drift would only surface as a read failure long after the mapping changed.
 *
 * <p>Round-trip fidelity is a correctness property here, not a nicety. Acceptance criterion 6
 * compares replayed silver against the original, so a value that changes shape on the way through
 * storage would make a correct replay look wrong -- or, worse, an incorrect one look right.
 */
final class IcebergSchemas {

    /** Field id for the canonical identity. Iceberg ids must be stable across schema evolution. */
    private static final int CANONICAL_ID_FIELD_ID = 1;

    /**
     * Decimal precision and scale. Iceberg requires both up front, unlike the canonical model.
     * Wide enough for any justice-domain quantity; the scale is the part worth revisiting if a
     * domain ever needs finer.
     */
    private static final int DECIMAL_PRECISION = 38;
    private static final int DECIMAL_SCALE = 9;

    private IcebergSchemas() {}

    /** Builds the Iceberg schema for a canonical type. */
    static Schema toIcebergSchema(CanonicalTypeDescriptor descriptor) {
        List<Types.NestedField> fields = new ArrayList<>();
        AtomicInteger nextId = new AtomicInteger(CANONICAL_ID_FIELD_ID);

        fields.add(Types.NestedField.required(
                nextId.getAndIncrement(),
                CanonicalTypeDescriptor.CANONICAL_ID_FIELD,
                Types.StringType.get()));

        // Roles first, matching the order Record.memberNames() uses, so a schema read back is
        // recognisably the same shape as the record that produced it.
        for (CanonicalRoleDescriptor role : descriptor.roles()) {
            fields.add(Types.NestedField.optional(
                    nextId.getAndIncrement(), role.name(), referenceType(nextId)));
        }
        for (CanonicalFieldDescriptor field : descriptor.fields()) {
            Type type = fieldType(field, nextId);
            fields.add(field.repeated()
                    ? Types.NestedField.optional(nextId.getAndIncrement(), field.name(),
                            Types.ListType.ofOptional(nextId.getAndIncrement(), type))
                    : Types.NestedField.optional(nextId.getAndIncrement(), field.name(), type));
        }
        return new Schema(fields);
    }

    /**
     * A canonical reference, as a struct of target type and identity.
     *
     * <p>A struct rather than a joined string: a reference is two facts, and flattening them means
     * something has to split the string again, which is a parsing rule nobody wrote down.
     */
    private static Type referenceType(AtomicInteger nextId) {
        return Types.StructType.of(
                Types.NestedField.required(nextId.getAndIncrement(), "typeName", Types.StringType.get()),
                Types.NestedField.required(nextId.getAndIncrement(), "id", Types.StringType.get()));
    }

    private static Type fieldType(CanonicalFieldDescriptor field, AtomicInteger nextId) {
        return switch (field.type()) {
            case STRING, CODE, IDENTITY -> Types.StringType.get();
            case DATE -> Types.DateType.get();
            // With zone: the canonical model stores instants, and a local timestamp column would
            // silently reinterpret them against whatever zone the reader happened to be in.
            case DATE_TIME -> Types.TimestampType.withZone();
            case INTEGER -> Types.LongType.get();
            case DECIMAL -> Types.DecimalType.of(DECIMAL_PRECISION, DECIMAL_SCALE);
            case BOOLEAN -> Types.BooleanType.get();
            case REF -> referenceType(nextId);
        };
    }

    // --- record conversion -----------------------------------------------

    /** Converts a canonical record into an Iceberg row. */
    static GenericRecord toIcebergRecord(CanonicalTypeDescriptor descriptor, Schema schema, Record record) {
        GenericRecord row = GenericRecord.create(schema);

        CanonicalId identity = record.get(CanonicalTypeDescriptor.CANONICAL_ID_FIELD, CanonicalId.class);
        row.setField(CanonicalTypeDescriptor.CANONICAL_ID_FIELD,
                identity == null ? null : identity.value());

        for (CanonicalRoleDescriptor role : descriptor.roles()) {
            row.setField(role.name(), toReference(schema, role.name(),
                    record.get(role.name(), CanonicalRef.class)));
        }
        for (CanonicalFieldDescriptor field : descriptor.fields()) {
            row.setField(field.name(), toIcebergValue(schema, field, record));
        }
        return row;
    }

    private static Object toIcebergValue(Schema schema, CanonicalFieldDescriptor field, Record record) {
        if (field.repeated()) {
            List<Object> values = new ArrayList<>();
            record.getList(field.name(), Object.class)
                    .forEach(element -> values.add(scalarToIceberg(field, element)));
            return values.isEmpty() ? null : values;
        }
        if (field.type() == gov.niemplatform.canonical.meta.FieldType.REF) {
            return toReference(schema, field.name(), record.get(field.name(), CanonicalRef.class));
        }
        return scalarToIceberg(field, record.raw(field.name()));
    }

    private static Object scalarToIceberg(CanonicalFieldDescriptor field, Object value) {
        if (value == null) {
            return null;
        }
        return switch (field.type()) {
            // Iceberg models a zoned timestamp as OffsetDateTime; the instant is unchanged.
            case DATE_TIME -> ((Instant) value).atOffset(ZoneOffset.UTC);
            case DECIMAL -> ((BigDecimal) value).setScale(DECIMAL_SCALE, java.math.RoundingMode.UNNECESSARY);
            default -> value;
        };
    }

    private static GenericRecord toReference(Schema schema, String fieldName, CanonicalRef reference) {
        if (reference == null) {
            return null;
        }
        Types.StructType struct = schema.findField(fieldName).type().asStructType();
        GenericRecord row = GenericRecord.create(struct);
        row.setField("typeName", reference.typeName());
        row.setField("id", reference.id().value());
        return row;
    }

    /** Converts an Iceberg row back into a canonical record. */
    static Record fromIcebergRecord(
            CanonicalTypeDescriptor descriptor, org.apache.iceberg.data.Record row) {

        Record.Builder builder = Record.builder(descriptor.qualifiedName());

        Object identity = row.getField(CanonicalTypeDescriptor.CANONICAL_ID_FIELD);
        builder.set(CanonicalTypeDescriptor.CANONICAL_ID_FIELD,
                identity == null ? null : CanonicalId.of(identity.toString()));

        for (CanonicalRoleDescriptor role : descriptor.roles()) {
            builder.set(role.name(), fromReference(row.getField(role.name())));
        }
        for (CanonicalFieldDescriptor field : descriptor.fields()) {
            builder.set(field.name(), fromIcebergValue(field, row.getField(field.name())));
        }
        return builder.build();
    }

    private static Object fromIcebergValue(CanonicalFieldDescriptor field, Object value) {
        if (value == null) {
            // Absent rather than an empty list: the canonical model does not distinguish them,
            // and Record.getList already treats absence as empty.
            return null;
        }
        if (field.repeated()) {
            List<Object> values = new ArrayList<>();
            ((List<?>) value).forEach(element -> values.add(scalarFromIceberg(field, element)));
            return List.copyOf(values);
        }
        if (field.type() == gov.niemplatform.canonical.meta.FieldType.REF) {
            return fromReference(value);
        }
        return scalarFromIceberg(field, value);
    }

    private static Object scalarFromIceberg(CanonicalFieldDescriptor field, Object value) {
        if (value == null) {
            return null;
        }
        return switch (field.type()) {
            case DATE_TIME -> ((OffsetDateTime) value).toInstant();
            case DATE -> (LocalDate) value;
            // Iceberg returns the stored scale; the canonical value is the same number, and
            // stripping trailing zeros keeps a round trip equal to what went in.
            case DECIMAL -> ((BigDecimal) value).stripTrailingZeros();
            default -> value;
        };
    }

    private static CanonicalRef fromReference(Object value) {
        if (value == null) {
            return null;
        }
        org.apache.iceberg.data.Record row = (org.apache.iceberg.data.Record) value;
        return CanonicalRef.to(
                String.valueOf(row.getField("typeName")),
                String.valueOf(row.getField("id")));
    }
}
