package gov.niemplatform.canonical.core;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalFieldDescriptor;
import gov.niemplatform.canonical.meta.CanonicalId;
import gov.niemplatform.canonical.meta.CanonicalKind;
import gov.niemplatform.canonical.meta.CanonicalRef;
import gov.niemplatform.canonical.meta.CanonicalRoleDescriptor;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import java.util.List;
import java.util.stream.Stream;

/**
 * Assertions over the generated Phase 1 canonical model.
 *
 * <p>Spec §4.1 limits Phase 1 to {@code Person}, {@code Incident}, and their association.
 * That boundary is asserted here rather than trusted, because "just one more type" is how a
 * vertical slice stops being a vertical slice.
 */
class CanonicalModelTest {

    @Test
    @DisplayName("Phase 1 declares exactly Person, Incident, and their association")
    void phaseOneScope() {
        assertThat(CoreCanonicalTypes.ALL)
                .extracting(CanonicalTypeDescriptor::name)
                .containsExactlyInAnyOrder("Person", "Incident", "PersonIncidentAssociation");
    }

    @TestFactory
    @DisplayName("every canonical construct declares provenance or a justified extension")
    Stream<DynamicTest> everyConstructIsAttributed() {
        return CoreCanonicalTypes.ALL.stream().map(type -> DynamicTest.dynamicTest(type.name(), () -> {
            assertThat(type.provenance() == null)
                    .as("%s declares exactly one of provenance or extension", type.name())
                    .isNotEqualTo(type.extension() == null);

            for (CanonicalFieldDescriptor field : type.fields()) {
                assertThat(field.provenance() == null)
                        .as("%s.%s declares exactly one of provenance or extension", type.name(), field.name())
                        .isNotEqualTo(field.extension() == null);
                if (field.isExtension()) {
                    assertThat(field.extension().text()).as("%s.%s justification", type.name(), field.name())
                            .isNotBlank();
                }
            }
            for (CanonicalRoleDescriptor role : type.roles()) {
                assertThat(role.provenance() == null)
                        .as("%s.%s declares exactly one of provenance or extension", type.name(), role.name())
                        .isNotEqualTo(role.extension() == null);
            }
        }));
    }

    @Test
    @DisplayName("extension types live outside the NIEM-derived namespace")
    void extensionsAreSegregated() {
        for (CanonicalTypeDescriptor type : CoreCanonicalTypes.ALL) {
            if (type.isExtension()) {
                assertThat(type.namespace())
                        .as("%s is an extension", type.name())
                        .startsWith("https://niemplatform.gov/canonical/extension/");
            } else {
                assertThat(type.namespace())
                        .as("%s is NIEM-derived", type.name())
                        .doesNotStartWith("https://niemplatform.gov/canonical/extension/");
            }
        }
    }

    @Test
    @DisplayName("the association is modelled with roles, not foreign keys")
    void associationHasRoles() {
        CanonicalTypeDescriptor assoc = CoreCanonicalTypes.byName("PersonIncidentAssociation").orElseThrow();

        assertThat(assoc.kind()).isEqualTo(CanonicalKind.ASSOCIATION);
        assertThat(assoc.roles()).extracting(CanonicalRoleDescriptor::name)
                .containsExactly("person", "incident");
        assertThat(assoc.roles()).extracting(CanonicalRoleDescriptor::targetType)
                .containsExactly("Person", "Incident");
    }

    @Test
    @DisplayName("types are addressable by qualified name, as carried on every record")
    void lookupByQualifiedName() {
        assertThat(CoreCanonicalTypes.byQualifiedName("https://niemplatform.gov/canonical/core/1.0#Person"))
                .get()
                .returns("Person", CanonicalTypeDescriptor::name);

        assertThat(CoreCanonicalTypes.byQualifiedName("https://example.invalid#Nope")).isEmpty();
    }

    @Test
    @DisplayName("a Person round-trips through the generic record form unchanged")
    void personRoundTrip() {
        Person original = new Person(
                CanonicalId.of("cluster-7"),
                "JANE",
                "M",
                "DOE",
                LocalDate.of(1988, 3, 14),
                "F",
                "K4471902",
                null);

        assertThat(Person.fromRecord(original.toRecord())).isEqualTo(original);
    }

    @Test
    @DisplayName("an Incident round-trips through the generic record form unchanged")
    void incidentRoundTrip() {
        Incident original = new Incident(
                CanonicalId.of("INC/RIVERTON-PD/2026-000114"),
                "2026-000114",
                Instant.parse("2026-03-04T17:20:00Z"),
                "418 W 9TH ST",
                "BURG",
                "3A");

        assertThat(Incident.fromRecord(original.toRecord())).isEqualTo(original);
    }

    @Test
    @DisplayName("an association round-trips with both role references intact")
    void associationRoundTrip() {
        PersonIncidentAssociation original = new PersonIncidentAssociation(
                CanonicalId.of("PIA/RIVERTON-PD/2026-000114/cluster-7"),
                CanonicalRef.to("Person", "cluster-7"),
                CanonicalRef.to("Incident", "INC/RIVERTON-PD/2026-000114"),
                "VICTIM");

        Record asRecord = original.toRecord();

        assertThat(PersonIncidentAssociation.fromRecord(asRecord)).isEqualTo(original);
        assertThat(asRecord.get("person", CanonicalRef.class).typeName()).isEqualTo("Person");
    }

    @Test
    @DisplayName("the record form carries the qualified type name")
    void recordCarriesQualifiedType() {
        Record record = new Person(CanonicalId.of("c1"), null, null, "DOE", null, null, null, null).toRecord();

        assertThat(record.typeName()).isEqualTo(Person.DESCRIPTOR.qualifiedName());
    }

    @Test
    @DisplayName("member names cover the canonical identity, roles, and fields")
    void memberNames() {
        List<String> members = CoreCanonicalTypes.byName("PersonIncidentAssociation").orElseThrow().memberNames();

        assertThat(members).containsExactly("canonicalId", "person", "incident", "involvementCode");
    }
}
