package gov.niemplatform.exchange.api;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.canonical.core.CoreCanonicalTypes;
import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalId;
import gov.niemplatform.canonical.meta.CanonicalRef;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A criminal history document is built by walking configuration, not by a method that knows it
 * (ADR 0034).
 *
 * <p>The document under test is the one the hard-coded writer could not produce at all: an arrest,
 * the person arrested, the booking it led to, the counts, and how each count ended. Nothing in
 * {@link DocumentAssembler} names any of those types.
 */
class AWalkBuildsTheDocumentTest {

    private static final List<CanonicalTypeDescriptor> MODEL = CoreCanonicalTypes.ALL;
    private static final Instant WHEN = Instant.parse("2026-03-04T11:20:00Z");

    /** The assembly a submission to a state repository actually needs. */
    private static final AssemblySpec CCH = new AssemblySpec("Arrest", List.of(
            new AssemblySpec.Follow("ArrestSubjectAssociation", "person", "subject", List.of()),
            new AssemblySpec.Follow("BookingArrestAssociation", "booking", "booking", List.of()),
            new AssemblySpec.Follow("ArrestChargeAssociation", "charge", "charges", List.of(
                    new AssemblySpec.Follow(
                            "ChargeDispositionAssociation", "disposition", "disposition", List.of()),
                    new AssemblySpec.Follow(
                            "ChargeSentenceAssociation", "sentence", "sentence", List.of())))));

    @Test
    @DisplayName("an arrest assembles with its subject, booking, counts and each count's outcome")
    void assemblesTheCchShape() {
        List<Record> silver = new ArrayList<>();
        silver.add(arrest("A1", "LEON-2026-0114"));
        silver.add(person("P1", "CHEN"));
        silver.add(booking("B1", "BK-9001"));
        silver.add(charge("C1", "784.03"));
        silver.add(charge("C2", "812.014"));
        silver.add(disposition("D1", "CONVICTED"));
        silver.add(sentence("S1"));
        silver.add(link("ArrestSubjectAssociation", "ASA1", "arrest", "Arrest", "A1", "person", "Person", "P1"));
        silver.add(link("BookingArrestAssociation", "BAA1", "arrest", "Arrest", "A1", "booking", "Booking", "B1"));
        silver.add(link("ArrestChargeAssociation", "ACA1", "arrest", "Arrest", "A1", "charge", "Charge", "C1"));
        silver.add(link("ArrestChargeAssociation", "ACA2", "arrest", "Arrest", "A1", "charge", "Charge", "C2"));
        silver.add(link("ChargeDispositionAssociation", "CDA1", "charge", "Charge", "C1", "disposition", "Disposition", "D1"));
        silver.add(link("ChargeSentenceAssociation", "CSA1", "charge", "Charge", "C1", "sentence", "Sentence", "S1"));

        DocumentAssembler.Assembly assembly = new DocumentAssembler(MODEL).assemble(CCH, silver);

        assertThat(assembly.documents()).hasSize(1);
        AssembledDocument document = assembly.documents().getFirst();
        assertThat(document.rootType()).isEqualTo("Arrest");
        assertThat(document.root().get("arrestAgencyRecordId", String.class)).isEqualTo("LEON-2026-0114");

        assertThat(document.elements().get("subject")).singleElement()
                .satisfies(element -> assertThat(element.record().get("surName", String.class))
                        .isEqualTo("CHEN"));
        assertThat(document.elements().get("booking")).hasSize(1);

        List<AssembledDocument.Element> charges = document.elements().get("charges");
        assertThat(charges).hasSize(2);
        assertThat(charges.getFirst().record().get("statuteCodeId", String.class)).isEqualTo("784.03");

        // The count that ended, with what ended it and what followed -- two levels down, from config.
        assertThat(charges.getFirst().elements().get("disposition")).singleElement()
                .satisfies(element -> assertThat(
                        element.record().get("dispositionCategoryCode", String.class))
                        .isEqualTo("CONVICTED"));
        assertThat(charges.getFirst().elements().get("sentence")).hasSize(1);

        // The second count has neither yet, which is the ordinary state of a pending charge.
        assertThat(charges.get(1).elements().get("disposition")).isEmpty();

        assertThat(document.size()).isEqualTo(7);
        assertThat(assembly.unresolved()).isEmpty();
    }

    /**
     * Changing what is submitted is changing the assembly, and nothing else.
     *
     * <p>The same records, a narrower document. Under the previous writer this was a code change.
     */
    @Test
    @DisplayName("a narrower assembly over the same records yields a narrower document")
    void configurationDecidesTheShape() {
        List<Record> silver = List.of(
                arrest("A1", "LEON-2026-0114"),
                person("P1", "CHEN"),
                charge("C1", "784.03"),
                link("ArrestSubjectAssociation", "ASA1", "arrest", "Arrest", "A1", "person", "Person", "P1"),
                link("ArrestChargeAssociation", "ACA1", "arrest", "Arrest", "A1", "charge", "Charge", "C1"));

        AssemblySpec subjectOnly = new AssemblySpec("Arrest", List.of(
                new AssemblySpec.Follow("ArrestSubjectAssociation", "person", "subject", List.of())));

        AssembledDocument document =
                new DocumentAssembler(MODEL).assemble(subjectOnly, silver).documents().getFirst();

        assertThat(document.elements()).containsOnlyKeys("subject");
        assertThat(document.size()).isEqualTo(2);
    }

    /**
     * A disposition that has not arrived yet and an identity that never resolved look identical
     * from in here, so the assembler reports and does not decide.
     */
    @Test
    @DisplayName("a reference to a record that was not supplied is reported, never dropped quietly")
    void danglingReferencesAreReported() {
        List<Record> silver = List.of(
                arrest("A1", "LEON-2026-0114"),
                link("ArrestSubjectAssociation", "ASA1", "arrest", "Arrest", "A1", "person", "Person", "GONE"));

        DocumentAssembler.Assembly assembly = new DocumentAssembler(MODEL).assemble(CCH, silver);

        assertThat(assembly.documents()).hasSize(1);
        assertThat(assembly.documents().getFirst().elements().get("subject")).isEmpty();
        assertThat(assembly.unresolved()).singleElement().satisfies(missing -> {
            assertThat(missing.fromType()).isEqualTo("Arrest");
            assertThat(missing.association()).isEqualTo("ArrestSubjectAssociation");
            assertThat(missing.role()).isEqualTo("person");
            assertThat(missing.targetRef()).contains("GONE");
        });
    }

    /**
     * Criterion 6's replay claim is worth nothing if replaying produces a different document, and a
     * repository cannot tell a reordered resubmission from a correction.
     */
    @Test
    @DisplayName("the same silver assembles to the same document however the records are ordered")
    void orderIsStable() {
        List<Record> silver = new ArrayList<>(List.of(
                arrest("A2", "LEON-2026-0200"),
                arrest("A1", "LEON-2026-0114"),
                charge("C2", "812.014"),
                charge("C1", "784.03"),
                link("ArrestChargeAssociation", "ACA2", "arrest", "Arrest", "A1", "charge", "Charge", "C2"),
                link("ArrestChargeAssociation", "ACA1", "arrest", "Arrest", "A1", "charge", "Charge", "C1")));

        DocumentAssembler assembler = new DocumentAssembler(MODEL);
        List<AssembledDocument> first = assembler.assemble(CCH, silver).documents();
        java.util.Collections.reverse(silver);
        List<AssembledDocument> second = assembler.assemble(CCH, silver).documents();

        assertThat(first).extracting(AssembledDocument::root).isEqualTo(
                second.stream().map(AssembledDocument::root).toList());
        assertThat(first.getFirst().root().get("arrestAgencyRecordId", String.class))
                .isEqualTo("LEON-2026-0114");
        assertThat(first.getFirst().elements().get("charges"))
                .extracting(element -> element.record().get("statuteCodeId", String.class))
                .containsExactly("784.03", "812.014");
    }

    @Test
    @DisplayName("one document per root record, and none for records nothing roots on")
    void oneDocumentPerRoot() {
        List<Record> silver = List.of(
                arrest("A1", "LEON-2026-0114"),
                arrest("A2", "LEON-2026-0200"),
                person("P1", "CHEN"));

        DocumentAssembler.Assembly assembly = new DocumentAssembler(MODEL).assemble(CCH, silver);

        assertThat(assembly.documents()).hasSize(2);
        assertThat(assembly.records()).isEqualTo(2);
    }

    // --- fixtures -------------------------------------------------------------------------------

    private static Record arrest(String id, String recordNumber) {
        return Record.builder("Arrest")
                .set("canonicalId", CanonicalId.of(id))
                .set("arrestAgencyRecordId", recordNumber)
                .set("arrestDateTime", WHEN)
                .set("arrestAgencyName", "Leon County SO")
                .build();
    }

    private static Record person(String id, String surName) {
        return Record.builder("Person")
                .set("canonicalId", CanonicalId.of(id))
                .set("surName", surName)
                .set("birthDate", LocalDate.of(1988, 3, 14))
                .build();
    }

    private static Record booking(String id, String recordNumber) {
        return Record.builder("Booking")
                .set("canonicalId", CanonicalId.of(id))
                .set("bookingAgencyRecordId", recordNumber)
                .set("bookingDateTime", WHEN)
                .set("bookingAgencyName", "Leon County SO")
                .build();
    }

    private static Record charge(String id, String statute) {
        return Record.builder("Charge")
                .set("canonicalId", CanonicalId.of(id))
                .set("chargeTrackingId", "TRK-" + id)
                .set("statuteCodeId", statute)
                .build();
    }

    private static Record disposition(String id, String category) {
        return Record.builder("Disposition")
                .set("canonicalId", CanonicalId.of(id))
                .set("dispositionId", "DISP-" + id)
                .set("dispositionDate", LocalDate.of(2026, 6, 1))
                .set("dispositionCategoryCode", category)
                .set("reportingAuthorityText", "Leon County Clerk of Court")
                .build();
    }

    private static Record sentence(String id) {
        return Record.builder("Sentence")
                .set("canonicalId", CanonicalId.of(id))
                .set("sentenceId", "SENT-" + id)
                .set("sentenceDate", LocalDate.of(2026, 6, 1))
                .build();
    }

    private static Record link(
            String associationType, String id,
            String roleA, String typeA, String idA,
            String roleB, String typeB, String idB) {
        return Record.builder(associationType)
                .set("canonicalId", CanonicalId.of(id))
                .set(roleA, CanonicalRef.to(typeA, idA))
                .set(roleB, CanonicalRef.to(typeB, idB))
                .build();
    }
}
