package gov.niemplatform.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalRef;
import gov.niemplatform.canonical.meta.FieldType;
import gov.niemplatform.observability.ContractViolation.Failure;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Schema validation, which is where semantic drift is actually caught.
 *
 * <p>Spec §4.2 names the failure mode that matters: not hard pipeline failures, but a source
 * quietly changing format while nothing errors. The pattern and unexpected-field checks below
 * are the two that catch it; everything else catches ordinary bad data.
 */
class SchemaValidatorTest {

    private static final SchemaValidator VALIDATOR = new SchemaValidator();

    /** A source-shaped schema, as a file drop connector would land it: everything text. */
    private static Schema cadPersonSchema() {
        return Schema.strict("source:cad-csv/person", "1.0.0", List.of(
                FieldExpectation.required("INC_NUM", FieldType.STRING)
                        .withPattern("^\\d{4}-\\d{6}$"),
                FieldExpectation.required("NAME_FULL", FieldType.STRING),
                FieldExpectation.optional("DOB", FieldType.STRING)
                        .withPattern("^\\d{2}/\\d{2}/\\d{4}$"),
                new FieldExpectation("ROLE", FieldType.CODE, true, false,
                        List.of("VICT", "SUSP", "WITN", "RP"), null, null)));
    }

    private static Record.Builder validCadPerson() {
        return Record.builder("source:cad-csv/person")
                .set("INC_NUM", "2026-000114")
                .set("NAME_FULL", "DOE, JANE M")
                .set("DOB", "03/14/1988")
                .set("ROLE", "VICT");
    }

    @Test
    @DisplayName("a conforming record passes with no failures")
    void conformingRecordPasses() {
        ValidationResult result = VALIDATOR.validate(validCadPerson().build(), cadPersonSchema());

        assertThat(result.isValid()).isTrue();
        assertThat(result.failures()).isEmpty();
    }

    @Nested
    @DisplayName("catches the quiet changes")
    class QuietChanges {

        @Test
        @DisplayName("a date format changing is caught even though both forms are valid strings")
        void dateFormatDrift() {
            Record drifted = validCadPerson().set("DOB", "1988-03-14").build();

            ValidationResult result = VALIDATOR.validate(drifted, cadPersonSchema());

            assertThat(result.isValid()).isFalse();
            assertThat(result.failures()).singleElement()
                    .returns("DOB", Failure::fieldName)
                    .returns("pattern", Failure::rule);
            assertThat(result.failures().getFirst().actual().pattern()).isEqualTo("####-##-##");
        }

        @Test
        @DisplayName("a source adding a column is a violation, not a shrug")
        void unexpectedFieldIsAViolation() {
            Record widened = validCadPerson().set("GANG_AFFIL", "UNK").build();

            ValidationResult result = VALIDATOR.validate(widened, cadPersonSchema());

            assertThat(result.failures()).singleElement()
                    .returns("GANG_AFFIL", Failure::fieldName)
                    .returns("unexpectedField", Failure::rule);
        }

        @Test
        @DisplayName("every unexpected field is named, not just the first")
        void everyUnexpectedFieldNamed() {
            Record widened = validCadPerson().set("GANG_AFFIL", "UNK").set("CLEARANCE", "N").build();

            ValidationResult result = VALIDATOR.validate(widened, cadPersonSchema());

            assertThat(result.failures()).extracting(Failure::fieldName)
                    .containsExactlyInAnyOrder("GANG_AFFIL", "CLEARANCE");
        }

        @Test
        @DisplayName("a schema may tolerate fields the platform does not care about")
        void toleranceIsOptIn() {
            Record widened = validCadPerson().set("GANG_AFFIL", "UNK").build();

            ValidationResult result =
                    VALIDATOR.validate(widened, cadPersonSchema().tolerantOfUnexpectedFields());

            assertThat(result.isValid()).isTrue();
        }
    }

    @Nested
    @DisplayName("ordinary bad data")
    class BadData {

        @Test
        @DisplayName("a missing required field fails")
        void missingRequired() {
            Record record = validCadPerson().remove("NAME_FULL").build();

            assertThat(VALIDATOR.validate(record, cadPersonSchema()).failures())
                    .singleElement()
                    .returns("NAME_FULL", Failure::fieldName)
                    .returns("required", Failure::rule);
        }

        @Test
        @DisplayName("an explicit null is as absent as a missing field")
        void explicitNullIsAbsent() {
            Record record = validCadPerson().set("NAME_FULL", null).build();

            assertThat(VALIDATOR.validate(record, cadPersonSchema()).failures())
                    .extracting(Failure::rule).containsExactly("required");
        }

        @Test
        @DisplayName("an absent optional field is fine")
        void absentOptional() {
            Record record = validCadPerson().remove("DOB").build();

            assertThat(VALIDATOR.validate(record, cadPersonSchema()).isValid()).isTrue();
        }

        @Test
        @DisplayName("a value of the wrong type fails on type, and stops there")
        void wrongType() {
            Record record = validCadPerson().set("INC_NUM", 20260114L).build();

            List<Failure> failures = VALIDATOR.validate(record, cadPersonSchema()).failures();

            // One failure, not two: the pattern check is meaningless once the type is wrong.
            assertThat(failures).singleElement().returns("type", Failure::rule);
        }

        @Test
        @DisplayName("a code outside its list fails")
        void codeOutsideList() {
            Record record = validCadPerson().set("ROLE", "OFFICER").build();

            assertThat(VALIDATOR.validate(record, cadPersonSchema()).failures())
                    .singleElement().returns("codeList", Failure::rule);
        }

        @Test
        @DisplayName("every failure is reported in one pass")
        void allFailuresAtOnce() {
            Record record = Record.builder("source:cad-csv/person")
                    .set("INC_NUM", "114")
                    .set("DOB", "1988-03-14")
                    .set("ROLE", "OFFICER")
                    .set("EXTRA", "x")
                    .build();

            List<Failure> failures = VALIDATOR.validate(record, cadPersonSchema()).failures();

            assertThat(failures).extracting(Failure::fieldName)
                    .containsExactlyInAnyOrder("INC_NUM", "NAME_FULL", "DOB", "ROLE", "EXTRA");
        }

        @Test
        @DisplayName("a null record is a failure, not an exception")
        void nullRecord() {
            ValidationResult result = VALIDATOR.validate(null, cadPersonSchema());

            assertThat(result.failures()).singleElement().returns("present", Failure::rule);
        }
    }

    @Nested
    @DisplayName("canonical schemas")
    class Canonical {

        private static final String PERSON = "https://niemplatform.gov/canonical/core/1.0#Person";

        private static Schema personSchema() {
            return Schema.strict(PERSON, "1.0.0", List.of(
                    FieldExpectation.required("canonicalId", FieldType.STRING),
                    FieldExpectation.required("surName", FieldType.STRING),
                    FieldExpectation.optional("birthDate", FieldType.DATE),
                    new FieldExpectation("sexCode", FieldType.CODE, false, false,
                            List.of("M", "F", "X", "U"), null, null),
                    new FieldExpectation("aliases", FieldType.STRING, false, true,
                            List.of(), null, null)));
        }

        @Test
        @DisplayName("a typed canonical record passes")
        void typedRecordPasses() {
            Record record = Record.builder(PERSON)
                    .set("canonicalId", "cluster-7")
                    .set("surName", "DOE")
                    .set("birthDate", LocalDate.of(1988, 3, 14))
                    .set("sexCode", "F")
                    .set("aliases", List.of("JD", "JANIE"))
                    .build();

            assertThat(VALIDATOR.validate(record, personSchema()).isValid()).isTrue();
        }

        @Test
        @DisplayName("a date arriving as text fails, which is the output-side mapping bug")
        void dateAsTextFails() {
            Record record = Record.builder(PERSON)
                    .set("canonicalId", "cluster-7")
                    .set("surName", "DOE")
                    .set("birthDate", "1988-03-14")
                    .build();

            assertThat(VALIDATOR.validate(record, personSchema()).failures())
                    .singleElement()
                    .returns("birthDate", Failure::fieldName)
                    .returns("type", Failure::rule);
        }

        @Test
        @DisplayName("a repeated field must actually be a list")
        void repeatedMustBeList() {
            Record record = Record.builder(PERSON)
                    .set("canonicalId", "cluster-7")
                    .set("surName", "DOE")
                    .set("aliases", "JD")
                    .build();

            assertThat(VALIDATOR.validate(record, personSchema()).failures())
                    .singleElement().returns("repeated", Failure::rule);
        }

        @Test
        @DisplayName("each element of a repeated field is checked")
        void repeatedElementsChecked() {
            Record record = Record.builder(PERSON)
                    .set("canonicalId", "cluster-7")
                    .set("surName", "DOE")
                    .set("aliases", List.of("JD", 42L))
                    .build();

            assertThat(VALIDATOR.validate(record, personSchema()).failures())
                    .singleElement()
                    .returns("aliases[]", Failure::fieldName)
                    .returns("type", Failure::rule);
        }

        @Test
        @DisplayName("a reference to the wrong canonical type fails")
        void referenceToWrongType() {
            Schema assoc = Schema.strict("assoc", "1.0.0", List.of(
                    new FieldExpectation("person", FieldType.REF, true, false, List.of(), "Person", null)));
            Record record = Record.builder("assoc")
                    .set("person", CanonicalRef.to("Vehicle", "v-1"))
                    .build();

            assertThat(VALIDATOR.validate(record, assoc).failures())
                    .singleElement().returns("refType", Failure::rule);
        }
    }

    @Test
    @DisplayName("no offending value survives into a failure")
    void failuresCarryNoValues() {
        Record record = validCadPerson().set("DOB", "1988-03-14").build();

        String rendered = VALIDATOR.validate(record, cadPersonSchema()).toString();

        assertThat(rendered).doesNotContain("1988", "03-14").contains("####-##-##");
    }
}
