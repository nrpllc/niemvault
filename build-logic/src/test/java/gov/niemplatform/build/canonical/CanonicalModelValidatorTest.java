package gov.niemplatform.build.canonical;

import static gov.niemplatform.build.canonical.CanonicalModelTestSupport.CORE_NS;
import static gov.niemplatform.build.canonical.CanonicalModelTestSupport.EXT_NS;
import static gov.niemplatform.build.canonical.CanonicalModelTestSupport.NIEM_CORE;
import static gov.niemplatform.build.canonical.CanonicalModelTestSupport.assertFailsWith;
import static gov.niemplatform.build.canonical.CanonicalModelTestSupport.load;
import static gov.niemplatform.build.canonical.CanonicalModelTestSupport.validIncident;
import static gov.niemplatform.build.canonical.CanonicalModelTestSupport.validPerson;
import static gov.niemplatform.build.canonical.CanonicalModelTestSupport.write;
import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.build.canonical.CanonicalDslException.Code;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The canonical model rules of spec §4.1, exercised as a contract.
 *
 * <p>These are the checks that keep provenance honest. If they weaken, the platform can ship a
 * canonical type that silently claims to be NIEM when it is not -- which is worse than having
 * no provenance at all, because downstream consumers would trust it.
 */
class CanonicalModelValidatorTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a well-formed NIEM-sourced entity validates")
    void validEntity() throws IOException {
        write(dir, "person.yaml", validPerson());

        var types = load(dir);

        assertThat(types).singleElement().satisfies(type -> {
            assertThat(type.name()).isEqualTo("Person");
            assertThat(type.kind()).isEqualTo(TypeDef.Kind.ENTITY);
            assertThat(type.isExtension()).isFalse();
            assertThat(type.provenance().niemType()).isEqualTo("nc:PersonType");
        });
    }

    @Nested
    @DisplayName("provenance is mandatory and exclusive")
    class ProvenanceRules {

        @Test
        @DisplayName("a type with neither provenance nor extension is rejected")
        void neither() throws IOException {
            write(dir, "orphan.yaml", """
                    type: Orphan
                    kind: entity
                    namespace: "%s"
                    version: "1.0.0"
                    fields:
                      - name: value
                        type: string
                        provenance:
                          niemNamespace: "%s"
                          niemElement: "nc:Value"
                    """.formatted(CORE_NS, NIEM_CORE));

            assertFailsWith(() -> load(dir), Code.PROVENANCE_REQUIRED);
        }

        @Test
        @DisplayName("a type claiming both provenance and extension is rejected")
        void both() throws IOException {
            write(dir, "confused.yaml", """
                    type: Confused
                    kind: entity
                    namespace: "%s"
                    version: "1.0.0"
                    provenance:
                      niemNamespace: "%s"
                      niemType: "nc:ConfusedType"
                    extension:
                      justification: "Cannot be both."
                    fields:
                      - name: value
                        type: string
                        provenance:
                          niemNamespace: "%s"
                          niemElement: "nc:Value"
                    """.formatted(CORE_NS, NIEM_CORE, NIEM_CORE));

            assertFailsWith(() -> load(dir), Code.PROVENANCE_CONFLICT);
        }

        @Test
        @DisplayName("a field with neither provenance nor extension is rejected")
        void fieldWithoutProvenance() throws IOException {
            write(dir, "person.yaml", """
                    type: Person
                    kind: entity
                    namespace: "%s"
                    version: "1.0.0"
                    provenance:
                      niemNamespace: "%s"
                      niemType: "nc:PersonType"
                    fields:
                      - name: smuggledIn
                        type: string
                    """.formatted(CORE_NS, NIEM_CORE));

            assertFailsWith(() -> load(dir), Code.PROVENANCE_REQUIRED);
        }

        @Test
        @DisplayName("an extension without a written justification is rejected")
        void extensionWithoutJustification() throws IOException {
            write(dir, "ext.yaml", """
                    type: LocalThing
                    kind: entity
                    namespace: "%s"
                    version: "1.0.0"
                    extension:
                      justification: "   "
                    fields:
                      - name: value
                        type: string
                        extension:
                          justification: "Agency-local value with no NIEM equivalent."
                    """.formatted(EXT_NS));

            assertFailsWith(() -> load(dir), Code.MISSING_KEY);
        }
    }

    @Nested
    @DisplayName("extensions are segregated by namespace")
    class NamespaceSegregation {

        @Test
        @DisplayName("an extension type outside the extension namespace is rejected")
        void extensionInCoreNamespace() throws IOException {
            write(dir, "ext.yaml", """
                    type: LocalThing
                    kind: entity
                    namespace: "%s"
                    version: "1.0.0"
                    extension:
                      justification: "Agency-local concept with no NIEM equivalent."
                    fields:
                      - name: value
                        type: string
                        extension:
                          justification: "Agency-local value."
                    """.formatted(CORE_NS));

            assertFailsWith(() -> load(dir), Code.EXTENSION_NAMESPACE_VIOLATION);
        }

        @Test
        @DisplayName("a NIEM-sourced type inside the extension namespace is rejected")
        void niemTypeInExtensionNamespace() throws IOException {
            write(dir, "person.yaml", """
                    type: Person
                    kind: entity
                    namespace: "%s"
                    version: "1.0.0"
                    provenance:
                      niemNamespace: "%s"
                      niemType: "nc:PersonType"
                    fields:
                      - name: surName
                        type: string
                        provenance:
                          niemNamespace: "%s"
                          niemElement: "nc:PersonSurName"
                    """.formatted(EXT_NS, NIEM_CORE, NIEM_CORE));

            assertFailsWith(() -> load(dir), Code.EXTENSION_NAMESPACE_VIOLATION);
        }
    }

    @Test
    @DisplayName("two canonical types must not claim the same NIEM type")
    void niemTypeRedefinition() throws IOException {
        write(dir, "person.yaml", validPerson());
        write(dir, "person-copy.yaml", validPerson().replace("type: Person", "type: PersonAgain"));

        assertFailsWith(() -> load(dir), Code.NIEM_TYPE_REDEFINITION);
    }

    @Nested
    @DisplayName("structural rules")
    class Structure {

        @Test
        @DisplayName("an association needs at least two roles")
        void associationNeedsTwoRoles() throws IOException {
            write(dir, "person.yaml", validPerson());
            write(dir, "assoc.yaml", """
                    type: LonelyAssociation
                    kind: association
                    namespace: "%s"
                    version: "1.0.0"
                    extension:
                      justification: "Pending verification of the NIEM association type."
                    roles:
                      - name: person
                        target: Person
                        extension:
                          justification: "Role of the extension association."
                    """.formatted(EXT_NS));

            assertFailsWith(() -> load(dir), Code.STRUCTURAL);
        }

        @Test
        @DisplayName("a role target must resolve to a declared entity")
        void unresolvedRoleTarget() throws IOException {
            write(dir, "person.yaml", validPerson());
            write(dir, "assoc.yaml", """
                    type: DanglingAssociation
                    kind: association
                    namespace: "%s"
                    version: "1.0.0"
                    extension:
                      justification: "Pending verification of the NIEM association type."
                    roles:
                      - name: person
                        target: Person
                        extension:
                          justification: "Role of the extension association."
                      - name: vehicle
                        target: Vehicle
                        extension:
                          justification: "Role of the extension association."
                    """.formatted(EXT_NS));

            assertFailsWith(() -> load(dir), Code.UNRESOLVED_REFERENCE);
        }

        @Test
        @DisplayName("an association between declared entities validates")
        void validAssociation() throws IOException {
            write(dir, "person.yaml", validPerson());
            write(dir, "incident.yaml", validIncident());
            write(dir, "assoc.yaml", """
                    type: PersonIncidentAssociation
                    kind: association
                    namespace: "%s"
                    version: "1.0.0"
                    extension:
                      justification: "Pending verification of the NIEM association type."
                    roles:
                      - name: person
                        target: Person
                        extension:
                          justification: "Role of the extension association."
                      - name: incident
                        target: Incident
                        extension:
                          justification: "Role of the extension association."
                    """.formatted(EXT_NS));

            var types = load(dir);

            assertThat(types).extracting(TypeDef::name)
                    .containsExactlyInAnyOrder("Person", "Incident", "PersonIncidentAssociation");
        }

        @Test
        @DisplayName("a mapping cannot declare the platform-assigned canonical identity")
        void reservedFieldName() throws IOException {
            write(dir, "person.yaml", """
                    type: Person
                    kind: entity
                    namespace: "%s"
                    version: "1.0.0"
                    provenance:
                      niemNamespace: "%s"
                      niemType: "nc:PersonType"
                    fields:
                      - name: canonicalId
                        type: string
                        provenance:
                          niemNamespace: "%s"
                          niemElement: "nc:PersonSurName"
                    """.formatted(CORE_NS, NIEM_CORE, NIEM_CORE));

            assertFailsWith(() -> load(dir), Code.INVALID_VALUE);
        }

        @Test
        @DisplayName("a code field must declare its code list")
        void codeWithoutCodeList() throws IOException {
            write(dir, "person.yaml", """
                    type: Person
                    kind: entity
                    namespace: "%s"
                    version: "1.0.0"
                    provenance:
                      niemNamespace: "%s"
                      niemType: "nc:PersonType"
                    fields:
                      - name: sexCode
                        type: code
                        provenance:
                          niemNamespace: "%s"
                          niemElement: "nc:PersonSexCode"
                    """.formatted(CORE_NS, NIEM_CORE, NIEM_CORE));

            assertFailsWith(() -> load(dir), Code.MISSING_KEY);
        }
    }

    @Test
    @DisplayName("every problem in a document is reported in one pass")
    void reportsAllProblemsAtOnce() throws IOException {
        write(dir, "broken.yaml", """
                type: broken
                kind: entity
                namespace: "not-a-uri"
                version: "one"
                extension:
                  justification: "Deliberately broken fixture."
                fields:
                  - name: Value
                    type: notAType
                    extension:
                      justification: "Deliberately broken fixture."
                """);

        try {
            load(dir);
        } catch (CanonicalDslException e) {
            assertThat(e.problems()).extracting(CanonicalDslException.Problem::code)
                    .contains(Code.INVALID_VALUE)
                    .hasSizeGreaterThanOrEqualTo(4);
            assertThat(e.getMessage()).contains("broken.yaml");
            return;
        }
        throw new AssertionError("expected the broken document to be rejected");
    }
}
