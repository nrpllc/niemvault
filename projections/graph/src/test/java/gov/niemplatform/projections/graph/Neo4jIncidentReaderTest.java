package gov.niemplatform.projections.graph;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalFieldDescriptor;
import gov.niemplatform.canonical.meta.CanonicalId;
import gov.niemplatform.canonical.meta.CanonicalKind;
import gov.niemplatform.canonical.meta.CanonicalRef;
import gov.niemplatform.canonical.meta.CanonicalRoleDescriptor;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.canonical.meta.ExtensionJustification;
import gov.niemplatform.canonical.meta.FieldType;
import gov.niemplatform.canonical.meta.NiemProvenance;
import gov.niemplatform.projections.api.CanonicalChangeSet;
import gov.niemplatform.projections.api.TypedRecords;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Reading incidents back out of the graph.
 *
 * <p>Against a real Neo4j, and written through the real {@link Neo4jProjectionWriter} rather than by
 * seeding nodes directly. A reader tested against hand-written Cypher proves the reader agrees with
 * the test, not that it agrees with what the platform actually writes — and the label and
 * relationship naming is exactly where those two silently diverge.
 */
@Tag("docker")
@Testcontainers
@DisplayName("Reading incidents from the graph")
class Neo4jIncidentReaderTest {

    private static final String PASSWORD = "niemplatform-test";
    private static final String NIEM_CORE =
            "https://docs.oasis-open.org/niemopen/ns/model/niem-core/6.0/";

    @Container
    private static final Neo4jContainer<?> NEO4J =
            new Neo4jContainer<>("neo4j:5.26").withAdminPassword(PASSWORD);

    private Neo4jProjectionWriter writer;
    private Neo4jIncidentReader reader;
    private Driver driver;

    @BeforeEach
    void setUp() {
        writer = new Neo4jProjectionWriter(NEO4J.getBoltUrl(), "neo4j", PASSWORD);
        reader = new Neo4jIncidentReader(NEO4J.getBoltUrl(), "neo4j", PASSWORD);
        driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("neo4j", PASSWORD));
        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run("MATCH (n) DETACH DELETE n").consume());
        }
        writer.apply(fixture());
    }

    @AfterEach
    void tearDown() {
        writer.close();
        reader.close();
        driver.close();
    }

    // --- the model -------------------------------------------------------

    private static CanonicalTypeDescriptor personType() {
        return new CanonicalTypeDescriptor("Person",
                "https://niemplatform.gov/canonical/core/1.0", "1.0.0", CanonicalKind.ENTITY,
                new NiemProvenance(NIEM_CORE, "nc:PersonType", null), null,
                List.of(
                        niemField("surName", FieldType.STRING),
                        niemField("givenName", FieldType.STRING),
                        niemField("middleName", FieldType.STRING),
                        niemField("birthDate", FieldType.DATE)),
                List.of());
    }

    private static CanonicalTypeDescriptor incidentType() {
        return new CanonicalTypeDescriptor("Incident",
                "https://niemplatform.gov/canonical/core/1.0", "1.0.0", CanonicalKind.ENTITY,
                new NiemProvenance(NIEM_CORE, "nc:IncidentType", null), null,
                List.of(
                        niemField("incidentNumber", FieldType.STRING),
                        niemField("reportedDateTime", FieldType.DATE_TIME),
                        niemField("locationAddressText", FieldType.STRING),
                        new CanonicalFieldDescriptor("callTypeCode", FieldType.STRING, false, false,
                                null, new ExtensionJustification("Agency dispatch vocabulary."),
                                List.of(), null),
                        new CanonicalFieldDescriptor("beat", FieldType.STRING, false, false,
                                null, new ExtensionJustification("Agency operational geography."),
                                List.of(), null)),
                List.of());
    }

    private static CanonicalTypeDescriptor associationType() {
        return new CanonicalTypeDescriptor("PersonIncidentAssociation",
                "https://niemplatform.gov/canonical/core/1.0", "1.0.0", CanonicalKind.ASSOCIATION,
                new NiemProvenance(NIEM_CORE, "nc:ActivityPersonAssociationType", null), null,
                List.of(new CanonicalFieldDescriptor("involvementCode", FieldType.CODE, true, false,
                        null, new ExtensionJustification("Agency role vocabulary."),
                        List.of("VICTIM", "SUSPECT", "WITNESS", "REPORTING_PARTY"), null)),
                List.of(
                        new CanonicalRoleDescriptor("person", "Person",
                                new NiemProvenance(NIEM_CORE, null, "nc:Person"), null),
                        new CanonicalRoleDescriptor("incident", "Incident",
                                new NiemProvenance(NIEM_CORE, null, "nc:Activity"), null)));
    }

    private static CanonicalFieldDescriptor niemField(String name, FieldType type) {
        return new CanonicalFieldDescriptor(name, type, false, false,
                new NiemProvenance(NIEM_CORE, null, "nc:" + name), null, List.of(), null);
    }

    private static Record person(String id, String surname, String given, String middle) {
        return Record.builder(personType().qualifiedName())
                .set("canonicalId", CanonicalId.of(id))
                .set("surName", surname)
                .set("givenName", given)
                .set("middleName", middle)
                .set("birthDate", LocalDate.of(1988, 3, 14))
                .build();
    }

    private static Record incident(String id, String number, String when, String address) {
        return Record.builder(incidentType().qualifiedName())
                .set("canonicalId", CanonicalId.of(id))
                .set("incidentNumber", number)
                .set("reportedDateTime", Instant.parse(when))
                .set("locationAddressText", address)
                .set("callTypeCode", "BURG")
                .set("beat", "3A")
                .build();
    }

    private static Record association(String id, String personId, String incidentId, String role) {
        return Record.builder(associationType().qualifiedName())
                .set("canonicalId", CanonicalId.of(id))
                .set("person", CanonicalRef.to("Person", personId))
                .set("incident", CanonicalRef.to("Incident", incidentId))
                .set("involvementCode", role)
                .build();
    }

    /**
     * Two incidents sharing one person, which is the shape the whole view exists for.
     *
     * <p>JANE DOE is a victim on the first and a victim again on the third; HIRO NAKAMURA is a
     * witness on the first only. The recurrence is the finding, and one incident cannot show it.
     */
    private static CanonicalChangeSet fixture() {
        return new CanonicalChangeSet("run-1", List.of(
                new TypedRecords(personType(), List.of(
                        person("cluster-doe", "DOE", "JANE", "M"),
                        person("cluster-nakamura", "NAKAMURA", "HIRO", null))),
                new TypedRecords(incidentType(), List.of(
                        incident("INC/114", "2026-000114", "2026-03-04T18:20:00Z", "418 W 9TH ST"),
                        incident("INC/231", "2026-000231", "2026-03-08T10:11:00Z", "418 W 9TH ST"))),
                new TypedRecords(associationType(), List.of(
                        association("PIA/1", "cluster-doe", "INC/114", "VICTIM"),
                        association("PIA/2", "cluster-nakamura", "INC/114", "WITNESS"),
                        association("PIA/3", "cluster-doe", "INC/231", "VICTIM")))));
    }

    // --- listing ---------------------------------------------------------

    @Nested
    @DisplayName("listing what landed")
    class Listing {

        @Test
        @DisplayName("returns each incident with how many people are on it")
        void listsIncidents() {
            List<IncidentView.Summary> incidents = reader.incidents(10);

            assertThat(incidents).hasSize(2);
            assertThat(incidents).extracting(IncidentView.Summary::incidentNumber)
                    // Most recently reported first: an operator opens this to see what just landed.
                    .containsExactly("2026-000231", "2026-000114");
            assertThat(incidents.getLast().people()).isEqualTo(2);
        }

        @Test
        @DisplayName("counts what is there, so a caller can say 'none yet' rather than 'not found'")
        void countsIncidents() {
            assertThat(reader.incidentCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("honours the limit")
        void limits() {
            assertThat(reader.incidents(1)).hasSize(1);
        }
    }

    // --- one incident ----------------------------------------------------

    @Nested
    @DisplayName("one incident")
    class One {

        @Test
        @DisplayName("carries the incident's own fields")
        void readsTheIncident() {
            IncidentView view = reader.incident("2026-000114").orElseThrow();

            assertThat(view.incident().incidentNumber()).isEqualTo("2026-000114");
            assertThat(view.incident().locationAddressText()).isEqualTo("418 W 9TH ST");
            assertThat(view.incident().beat()).isEqualTo("3A");
            assertThat(view.incident().canonicalId()).isEqualTo("INC/114");
        }

        @Test
        @DisplayName("lists everyone on it with the role they had")
        void readsInvolvements() {
            IncidentView view = reader.incident("2026-000114").orElseThrow();

            assertThat(view.involvements()).hasSize(2);
            assertThat(view.involvementOf("cluster-doe").orElseThrow().involvementCode())
                    .isEqualTo("VICTIM");
            assertThat(view.involvementOf("cluster-nakamura").orElseThrow().involvementCode())
                    .isEqualTo("WITNESS");
        }

        @Test
        @DisplayName("finds a person's other incidents, which is the reason this is a graph")
        void readsConnections() {
            IncidentView view = reader.incident("2026-000114").orElseThrow();

            assertThat(view.hasConnections()).isTrue();
            assertThat(view.connections()).singleElement().satisfies(connection -> {
                assertThat(connection.person().canonicalId()).isEqualTo("cluster-doe");
                assertThat(connection.other().incidentNumber()).isEqualTo("2026-000231");
                assertThat(connection.involvementHere()).isEqualTo("VICTIM");
                assertThat(connection.involvementThere()).isEqualTo("VICTIM");
            });
        }

        @Test
        @DisplayName("does not report an incident as connected to itself")
        void excludesItself() {
            // The same person on the same incident matches the traversal twice without the guard,
            // and an incident listed as connected to itself is noise on every single view.
            IncidentView view = reader.incident("2026-000114").orElseThrow();

            assertThat(view.connections())
                    .noneMatch(connection -> connection.other().incidentNumber().equals("2026-000114"));
        }

        @Test
        @DisplayName("is empty for a number the graph does not hold")
        void unknownIncidentIsEmpty() {
            // An ordinary answer to an ordinary question: it may not have landed, or been mistyped.
            assertThat(reader.incident("2026-999999")).isEmpty();
        }

        @Test
        @DisplayName("reads a person whose middle name is absent")
        void toleratesAnAbsentProperty() {
            // Absent values are omitted rather than written as null by the projection, so the
            // property is not there at all -- not there holding null.
            IncidentView view = reader.incident("2026-000114").orElseThrow();
            IncidentView.Person hiro =
                    view.involvementOf("cluster-nakamura").orElseThrow().person();

            assertThat(hiro.middleName()).isNull();
            assertThat(hiro.displayName()).isEqualTo("HIRO NAKAMURA");
        }
    }

    // --- ADR 0015 --------------------------------------------------------

    @Nested
    @DisplayName("printing a view")
    class Printing {

        @Test
        @DisplayName("never prints a record value, per ADR 0015")
        void redactsValues() {
            // Inherited obligation, and the compiler does not enforce it. A view is if anything more
            // exposed than a record: it is assembled to be looked at, so it reaches log lines and
            // exception messages a canonical record never does.
            IncidentView view = reader.incident("2026-000114").orElseThrow();

            String printed = view + " " + view.incident() + " " + view.involvements()
                    + " " + view.connections();

            assertThat(printed)
                    .doesNotContain("DOE", "JANE", "NAKAMURA", "HIRO", "1988", "418 W 9TH ST", "3A");
            // The incident number stays: it is the agency's own reference, the thing the caller
            // asked about, and redacting it leaves a message that names nothing.
            assertThat(printed).contains("2026-000114");
        }
    }
}
