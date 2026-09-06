package gov.niemplatform.contracts;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalRef;
import gov.niemplatform.canonical.meta.FieldType;
import gov.niemplatform.observability.ContractViolation.Failure;
import gov.niemplatform.observability.ValueShape;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Checks a record against a schema (spec §4.2).
 *
 * <p>Collects every failure rather than stopping at the first, and describes offending values as
 * shapes rather than quoting them, so the result is safe to put straight into an event.
 *
 * <p>Stateless and thread-safe: instances are shared across Flink operator threads.
 */
public final class SchemaValidator {

    /**
     * Validates one record, returning every expectation it failed.
     *
     * <p>A null record is itself a failure rather than an exception -- a hop that produced
     * nothing must be quarantined and reported like any other bad output, not throw inside an
     * operator.
     */
    public ValidationResult validate(Record record, Schema schema) {
        if (record == null) {
            return ValidationResult.invalid(List.of(new Failure(
                    null, "present", "a record", ValueShape.absent())));
        }

        List<Failure> failures = new ArrayList<>();
        for (FieldExpectation expectation : schema.fields()) {
            checkField(record, expectation, failures);
        }
        if (!schema.allowUnexpectedFields()) {
            checkForUnexpectedFields(record, schema, failures);
        }
        return failures.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(failures);
    }

    private void checkField(Record record, FieldExpectation expectation, List<Failure> failures) {
        String name = expectation.name();
        Object value = record.raw(name);

        if (value == null) {
            if (expectation.required()) {
                failures.add(new Failure(name, "required", "a value", ValueShape.absent()));
            }
            return;
        }

        if (expectation.repeated()) {
            checkRepeated(name, expectation, value, failures);
            return;
        }
        checkSingle(name, expectation, value, failures);
    }

    private void checkRepeated(String name, FieldExpectation expectation, Object value, List<Failure> failures) {
        if (!(value instanceof List<?> list)) {
            failures.add(new Failure(name, "repeated", "a list of " + expectation.type(), ValueShape.of(value)));
            return;
        }
        for (Object element : list) {
            if (element != null) {
                checkSingle(name + "[]", expectation, element, failures);
            }
        }
    }

    private void checkSingle(String name, FieldExpectation expectation, Object value, List<Failure> failures) {
        FieldType type = expectation.type();

        if (!type.javaType().isInstance(value)) {
            failures.add(new Failure(name, "type",
                    type.name() + " (" + type.javaType().getSimpleName() + ")", ValueShape.of(value)));
            // Every remaining check assumes the declared type, so stop here for this value.
            return;
        }

        if (type == FieldType.CODE && !expectation.codeList().isEmpty()
                && !expectation.codeList().contains((String) value)) {
            failures.add(new Failure(name, "codeList",
                    "one of " + expectation.codeList(), ValueShape.of(value)));
        }

        if (type == FieldType.REF && expectation.refType() != null) {
            CanonicalRef ref = (CanonicalRef) value;
            if (!expectation.refType().equals(ref.typeName())) {
                failures.add(new Failure(name, "refType",
                        "a reference to " + expectation.refType(),
                        new ValueShape("CanonicalRef", null, ref.typeName())));
            }
        }

        Pattern pattern = expectation.compiledPattern();
        if (pattern != null && value instanceof CharSequence text && !pattern.matcher(text).matches()) {
            failures.add(new Failure(name, "pattern", "text matching " + expectation.pattern(),
                    ValueShape.of(value)));
        }
    }

    /**
     * A field the schema does not declare is a violation.
     *
     * <p>This is the check that catches a source quietly adding a column -- nothing else would.
     * It fires per unexpected field so the event names all of them at once.
     */
    private void checkForUnexpectedFields(Record record, Schema schema, List<Failure> failures) {
        List<String> declared = schema.fieldNames();
        for (String present : record.fieldNames()) {
            if (!declared.contains(present)) {
                failures.add(new Failure(present, "unexpectedField",
                        "no field beyond " + declared, ValueShape.of(record.raw(present))));
            }
        }
    }
}
