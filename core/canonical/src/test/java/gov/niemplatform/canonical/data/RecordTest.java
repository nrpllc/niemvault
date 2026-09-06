package gov.niemplatform.canonical.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.canonical.meta.CanonicalId;
import gov.niemplatform.canonical.meta.CanonicalRef;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Record semantics that the acceptance criteria depend on.
 *
 * <p>Two properties here are load-bearing rather than incidental. Field-order-independent
 * equality is what lets criterion 7 compare batch output to streaming output, and criterion 6
 * compare replayed silver to original silver, without either depending on operator scheduling.
 * And values never appearing in {@code toString} is what keeps criminal justice PII out of logs.
 */
class RecordTest {

    private static final String TYPE = "https://niemplatform.gov/canonical/core/1.0#Person";

    @Test
    @DisplayName("equality ignores field ordering")
    void equalityIsOrderIndependent() {
        Record first = Record.builder(TYPE)
                .set("surName", "DOE")
                .set("givenName", "JANE")
                .build();
        Record second = Record.builder(TYPE)
                .set("givenName", "JANE")
                .set("surName", "DOE")
                .build();

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    @Test
    @DisplayName("records of different types are never equal")
    void typeNameParticipatesInEquality() {
        Record person = Record.builder(TYPE).set("surName", "DOE").build();
        Record other = Record.builder("source:cad-csv/person").set("surName", "DOE").build();

        assertThat(person).isNotEqualTo(other);
    }

    @Test
    @DisplayName("insertion order is preserved for output stability")
    void insertionOrderPreserved() {
        Record record = Record.builder(TYPE)
                .set("surName", "DOE")
                .set("givenName", "JANE")
                .set("birthDate", LocalDate.of(1988, 3, 14))
                .build();

        assertThat(record.fieldNames()).containsExactly("surName", "givenName", "birthDate");
    }

    @Test
    @DisplayName("toString exposes field names but never values")
    void toStringRedactsValues() {
        Record record = Record.builder(TYPE)
                .set("surName", "DOE")
                .set("socialSecurityId", "123-45-6789")
                .build();

        assertThat(record.toString())
                .contains("surName", "socialSecurityId")
                .doesNotContain("DOE", "123-45-6789");
    }

    @Test
    @DisplayName("typed access returns null for an absent field")
    void absentFieldIsNull() {
        Record record = Record.builder(TYPE).set("surName", "DOE").build();

        assertThat(record.get("givenName", String.class)).isNull();
        assertThat(record.has("givenName")).isFalse();
    }

    @Test
    @DisplayName("an explicit null is present but has no value")
    void explicitNullIsPresent() {
        Record record = Record.builder(TYPE).set("givenName", null).build();

        assertThat(record.has("givenName")).isTrue();
        assertThat(record.hasValue("givenName")).isFalse();
    }

    @Test
    @DisplayName("setIfPresent leaves a null-valued field absent entirely")
    void setIfPresentSkipsNull() {
        Record record = Record.builder(TYPE).setIfPresent("givenName", null).build();

        assertThat(record.has("givenName")).isFalse();
    }

    @Test
    @DisplayName("a type mismatch fails loudly and structurally")
    void typeMismatchThrows() {
        Record record = Record.builder(TYPE).set("birthDate", "1988-03-14").build();

        assertThatThrownBy(() -> record.get("birthDate", LocalDate.class))
                .isInstanceOf(RecordTypeMismatchException.class)
                .satisfies(thrown -> {
                    var mismatch = (RecordTypeMismatchException) thrown;
                    assertThat(mismatch.fieldName()).isEqualTo("birthDate");
                    assertThat(mismatch.expectedType()).isEqualTo(LocalDate.class);
                    assertThat(mismatch.actualType()).isEqualTo(String.class);
                    assertThat(mismatch.recordTypeName()).isEqualTo(TYPE);
                });
    }

    @Test
    @DisplayName("a repeated field reads as an empty list when absent")
    void repeatedFieldDefaultsToEmpty() {
        Record record = Record.builder(TYPE).build();

        assertThat(record.getList("aliases", String.class)).isEmpty();
    }

    @Test
    @DisplayName("a wrongly typed list element fails rather than being coerced")
    void listElementMismatchThrows() {
        Record record = Record.builder(TYPE).set("aliases", List.of("JD", 42)).build();

        assertThatThrownBy(() -> record.getList("aliases", String.class))
                .isInstanceOf(RecordTypeMismatchException.class);
    }

    @Test
    @DisplayName("references carry their target type so they resolve without the mapping")
    void referencesAreTyped() {
        CanonicalRef ref = CanonicalRef.to("Person", CanonicalId.of("cluster-7"));
        Record record = Record.builder("assoc").set("person", ref).build();

        assertThat(record.get("person", CanonicalRef.class))
                .returns("Person", CanonicalRef::typeName)
                .returns(CanonicalId.of("cluster-7"), CanonicalRef::id);
    }

    @Test
    @DisplayName("values() is unmodifiable so a hop cannot mutate a record in flight")
    void valuesAreUnmodifiable() {
        Record record = Record.builder(TYPE).set("surName", "DOE").build();

        assertThatThrownBy(() -> record.values().put("surName", "SMITH"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("toBuilder copies without aliasing the original")
    void toBuilderDoesNotAlias() {
        Record original = Record.builder(TYPE).set("surName", "DOE").build();

        Record derived = original.toBuilder().set("givenName", "JANE").build();

        assertThat(original.has("givenName")).isFalse();
        assertThat(derived.get("surName", String.class)).isEqualTo("DOE");
        assertThat(derived.get("givenName", String.class)).isEqualTo("JANE");
    }

    @Test
    @DisplayName("withTypeName re-labels a record as a canonicalising hop does")
    void withTypeName() {
        Record source = Record.builder("source:cad-csv/person").set("surName", "DOE").build();

        Record canonical = source.withTypeName(TYPE);

        assertThat(canonical.typeName()).isEqualTo(TYPE);
        assertThat(canonical.get("surName", String.class)).isEqualTo("DOE");
        assertThat(source.typeName()).isEqualTo("source:cad-csv/person");
    }
}
