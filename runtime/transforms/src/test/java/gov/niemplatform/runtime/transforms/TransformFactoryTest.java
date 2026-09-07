package gov.niemplatform.runtime.transforms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.canonical.data.Record;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The transformation primitives (spec §5).
 *
 * <p>Two behaviours are asserted repeatedly because everything downstream rests on them: absence
 * passes through as absence and is the contract's problem, while a value that is <em>present but
 * wrong</em> raises rather than quietly becoming null. Emitting null on a parse failure would let
 * a source change its date format and lose every date with nothing to show for it.
 */
class TransformFactoryTest {

    private static final String SOURCE_TYPE = "source:cad-csv/person";

    private static Object evaluate(TransformSpec spec, Map<String, Object> fields) {
        Record.Builder builder = Record.builder(SOURCE_TYPE);
        fields.forEach(builder::set);
        return TransformFactory.create(spec).evaluate(new TransformInput(builder.build(), Map.of()));
    }

    private static Object evaluate(TransformSpec spec, String field, String value) {
        Record record = Record.builder(SOURCE_TYPE).set(field, value).build();
        return TransformFactory.create(spec).evaluate(new TransformInput(record, Map.of()));
    }

    @Nested
    @DisplayName("text primitives")
    class TextPrimitives {

        @Test
        @DisplayName("copy carries a value through unchanged")
        void copy() {
            assertThat(evaluate(TransformSpec.of("out", "copy", "IN"), "IN", " DOE ")).isEqualTo(" DOE ");
        }

        @Test
        @DisplayName("an absent source yields an absent target, not an error")
        void absenceIsNotAnError() {
            assertThat(evaluate(TransformSpec.of("out", "copy", "IN"), Map.of())).isNull();
        }

        @Test
        @DisplayName("a blank source counts as absent")
        void blankIsAbsent() {
            assertThat(evaluate(TransformSpec.of("out", "trim", "IN"), "IN", "   ")).isNull();
        }

        @Test
        @DisplayName("trim, upper, lower and collapseSpace do what they say")
        void simpleText() {
            assertThat(evaluate(TransformSpec.of("out", "trim", "IN"), "IN", "  DOE  ")).isEqualTo("DOE");
            assertThat(evaluate(TransformSpec.of("out", "upper", "IN"), "IN", "doe")).isEqualTo("DOE");
            assertThat(evaluate(TransformSpec.of("out", "lower", "IN"), "IN", "DOE")).isEqualTo("doe");
            assertThat(evaluate(TransformSpec.of("out", "collapseSpace", "IN"), "IN", " DOE   JANE "))
                    .isEqualTo("DOE JANE");
        }

        @Test
        @DisplayName("digitsOnly normalises a licence number for identity resolution")
        void digitsOnly() {
            assertThat(evaluate(TransformSpec.of("out", "digitsOnly", "IN"), "IN", "K447-1902"))
                    .isEqualTo("4471902");
            assertThat(evaluate(TransformSpec.of("out", "digitsOnly", "IN"), "IN", "NONE")).isNull();
        }

        @Test
        @DisplayName("literal writes a constant regardless of input")
        void literal() {
            assertThat(evaluate(new TransformSpec("out", "literal", java.util.List.of(),
                    Map.of("value", "RIVERTON-PD")), Map.of())).isEqualTo("RIVERTON-PD");
        }
    }

    @Nested
    @DisplayName("source messiness")
    class Messiness {

        @Test
        @DisplayName("nullIf maps an agency sentinel to absence")
        void nullIfSentinels() {
            TransformSpec spec = TransformSpec.of("out", "nullIf", "IN", Map.of("values", "UNK,N/A,NONE"));

            assertThat(evaluate(spec, "IN", "UNK")).isNull();
            assertThat(evaluate(spec, "IN", "n/a")).isNull();
            assertThat(evaluate(spec, "IN", "K4471902")).isEqualTo("K4471902");
        }

        @Test
        @DisplayName("splitIndex pulls the parts out of a packed name field")
        void splitPackedName() {
            TransformSpec surname = TransformSpec.of("out", "splitIndex", "NAME_FULL",
                    Map.of("delimiter", ",", "index", "0"));
            TransformSpec rest = TransformSpec.of("out", "splitIndex", "NAME_FULL",
                    Map.of("delimiter", ",", "index", "1"));

            assertThat(evaluate(surname, "NAME_FULL", "DOE, JANE M")).isEqualTo("DOE");
            assertThat(evaluate(rest, "NAME_FULL", "DOE, JANE M")).isEqualTo("JANE M");
        }

        @Test
        @DisplayName("a name with no comma yields a surname and no given name")
        void splitMissingPartIsAbsent() {
            TransformSpec given = TransformSpec.of("out", "splitIndex", "NAME_FULL",
                    Map.of("delimiter", ",", "index", "1"));

            assertThat(evaluate(given, "NAME_FULL", "DOE")).isNull();
        }

        @Test
        @DisplayName("regexExtract takes a capture group")
        void regexExtract() {
            TransformSpec spec = TransformSpec.of("out", "regexExtract", "IN",
                    Map.of("pattern", "^(\\w+)\\s", "group", "1"));

            assertThat(evaluate(spec, "IN", "JANE M")).isEqualTo("JANE");
        }

        @Test
        @DisplayName("concat joins fields and skips the absent ones")
        void concat() {
            TransformSpec spec = new TransformSpec("out", "concat",
                    java.util.List.of("A", "B", "C"), Map.of("separator", " "));

            assertThat(evaluate(spec, Map.of("A", "JANE", "C", "DOE"))).isEqualTo("JANE DOE");
        }

        @Test
        @DisplayName("coalesce takes the first field that has a value")
        void coalesce() {
            TransformSpec spec = new TransformSpec("out", "coalesce",
                    java.util.List.of("A", "B"), Map.of());

            assertThat(evaluate(spec, Map.of("B", "fallback"))).isEqualTo("fallback");
            assertThat(evaluate(spec, Map.of("A", "first", "B", "fallback"))).isEqualTo("first");
        }
    }

    @Nested
    @DisplayName("typed parsing")
    class TypedParsing {

        @Test
        @DisplayName("parseDate produces a real date from the declared pattern")
        void parseDate() {
            TransformSpec spec = TransformSpec.of("out", "parseDate", "DOB", Map.of("pattern", "MM/dd/yyyy"));

            assertThat(evaluate(spec, "DOB", "03/14/1988")).isEqualTo(LocalDate.of(1988, 3, 14));
        }

        @Test
        @DisplayName("a date in a different format raises rather than silently becoming null")
        void driftedDateRaises() {
            TransformSpec spec = TransformSpec.of("out", "parseDate", "DOB", Map.of("pattern", "MM/dd/yyyy"));

            assertThatThrownBy(() -> evaluate(spec, "DOB", "1988-03-14"))
                    .isInstanceOf(TransformException.class)
                    .satisfies(thrown -> {
                        TransformException failure = (TransformException) thrown;
                        assertThat(failure.target()).isEqualTo("out");
                        assertThat(failure.sourceField()).isEqualTo("DOB");
                        assertThat(failure.actualShape()).isEqualTo("####-##-##");
                    });
        }

        @Test
        @DisplayName("a transform failure never quotes the offending value")
        void failureRedactsValue() {
            TransformSpec spec = TransformSpec.of("out", "parseDate", "DOB", Map.of("pattern", "MM/dd/yyyy"));

            assertThatThrownBy(() -> evaluate(spec, "DOB", "1988-03-14"))
                    .hasMessageNotContaining("1988")
                    .hasMessageContaining("####-##-##");
        }

        @Test
        @DisplayName("parseDateTime applies the mapping's declared zone rather than guessing")
        void parseDateTimeUsesDeclaredZone() {
            TransformSpec spec = TransformSpec.of("out", "parseDateTime", "RPT_DTTM",
                    Map.of("pattern", "yyyy/MM/dd HH:mm", "zone", "America/Denver"));

            // 11:20 Mountain Daylight Time on 4 March 2026 is 18:20 UTC.
            assertThat(evaluate(spec, "RPT_DTTM", "2026/03/04 11:20"))
                    .isEqualTo(Instant.parse("2026-03-04T18:20:00Z"));
        }

        @Test
        @DisplayName("parseInteger and parseDecimal raise on non-numeric input")
        void numericParsing() {
            assertThat(evaluate(TransformSpec.of("out", "parseInteger", "IN"), "IN", "42")).isEqualTo(42L);
            assertThatThrownBy(() -> evaluate(TransformSpec.of("out", "parseInteger", "IN"), "IN", "forty"))
                    .isInstanceOf(TransformException.class);
        }
    }

    @Nested
    @DisplayName("code mapping")
    class CodeMapping {

        private static final TransformSpec ROLE = TransformSpec.of("out", "codeMap", "ROLE",
                Map.of("map", "VICT=VICTIM,SUSP=SUSPECT,WITN=WITNESS,RP=REPORTING_PARTY"));

        @Test
        @DisplayName("an agency code maps onto the canonical code list")
        void mapsKnownCode() {
            assertThat(evaluate(ROLE, "ROLE", "VICT")).isEqualTo("VICTIM");
            assertThat(evaluate(ROLE, "ROLE", "vict")).isEqualTo("VICTIM");
        }

        @Test
        @DisplayName("an unmapped code raises, because a new dispatch code is a real change")
        void unmappedCodeRaises() {
            assertThatThrownBy(() -> evaluate(ROLE, "ROLE", "OFFICER"))
                    .isInstanceOf(TransformException.class);
        }

        @Test
        @DisplayName("a mapping that wants a catch-all says so explicitly")
        void explicitDefault() {
            TransformSpec withDefault = TransformSpec.of("out", "codeMap", "ROLE",
                    Map.of("map", "VICT=VICTIM", "default", "OTHER"));

            assertThat(evaluate(withDefault, "ROLE", "OFFICER")).isEqualTo("OTHER");
        }
    }

    @Nested
    @DisplayName("specs are validated at compile time, not on the first record")
    class SpecValidation {

        @Test
        @DisplayName("an unknown transform type is rejected")
        void unknownType() {
            assertThatThrownBy(() -> TransformFactory.create(TransformSpec.of("out", "teleport", "IN")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("teleport");
        }

        @Test
        @DisplayName("a misspelled option is rejected rather than ignored")
        void unknownOption() {
            assertThatThrownBy(() -> TransformFactory.create(
                    TransformSpec.of("out", "parseDate", "DOB", Map.of("patern", "MM/dd/yyyy"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("patern");
        }

        @Test
        @DisplayName("a missing required option is rejected")
        void missingOption() {
            assertThatThrownBy(() -> TransformFactory.create(TransformSpec.of("out", "parseDate", "DOB")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pattern");
        }

        @Test
        @DisplayName("parseDateTime requires an explicit zone")
        void zoneIsRequired() {
            assertThatThrownBy(() -> TransformFactory.create(TransformSpec.of("out", "parseDateTime", "IN",
                    Map.of("pattern", "yyyy/MM/dd HH:mm"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("zone");
        }
    }

    @Test
    @DisplayName("a step can read what an earlier step in the same hop emitted")
    void stepsChain() {
        Record source = Record.builder(SOURCE_TYPE).set("NAME_FULL", "doe, jane m").build();
        Transform surname = TransformFactory.create(TransformSpec.of("surName", "splitIndex", "NAME_FULL",
                Map.of("delimiter", ",", "index", "0")));
        Transform upper = TransformFactory.create(TransformSpec.of("surName", "upper", "surName"));

        Object split = surname.evaluate(new TransformInput(source, Map.of()));
        Object uppered = upper.evaluate(new TransformInput(source, Map.of("surName", split)));

        assertThat(uppered).isEqualTo("DOE");
    }
}
