package gov.niemplatform.projections.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import gov.niemplatform.projections.api.CanonicalSnapshot;
import gov.niemplatform.projections.api.ProjectionException;
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
 * Acceptance criterion 4: the graph projection contains the expected nodes and the association
 * edge.
 *
 * <p>Run against a real Neo4j rather than a fake. Spec §9 calls for testcontainers on integration
 * tests, and a graph writer verified only against a mock proves nothing about Cypher correctness,
 * MERGE semantics, or what happens when an endpoint is missing.
 */
@Tag("docker")
@Testcontainers
class Neo4jProjectionWriterTest {

    private static final String PASSWORD = "niemplatform-test";
    private static final String NIEM_CORE =
            "https://docs.oasis-open.org/niemopen/ns/model/niem-core/6.0/";

    @Container
    private static final Neo4jContainer<?> NEO4J =
            new Neo4jContainer<>("neo4j:5.26").withAdminPassword(PASSWORD);

    private Neo4jProjectionWriter writer;
    private Driver driver;

    @BeforeEach
    void setUp() {
        writer = new Neo4jProjectionWriter(NEO4J.getBoltUrl(), "neo4j", PASSWORD);
        driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("neo4j", PASSWORD));
        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run("MATCH (n) DETACH DELETE n").consume());
        }
    }

    @AfterEach
    void tearDown() {
        writer.close();
        driver.close();
    }

    // --- model -----------------------------------------------------------

    private static CanonicalTypeDescriptor personType() {
        return new CanonicalTypeDescriptor("Person",
                "https://niemplatform.gov/canonical/core/1.0", "1.0.0", CanonicalKind.ENTITY,
                new NiemProvenance(NIEM_CORE, "nc:PersonType", null), null,
                List.of(
                        niemField("surName", FieldType.STRING),
                        niemField("givenName", FieldType.STRING),
                        niemField("birthDate", FieldType.DATE)),
                List.of());
    }

    private static CanonicalTypeDescriptor incidentType() {
        return new CanonicalTypeDescriptor("Incident",
                "https://niemplatform.gov/canonical/core/1.0", "1.0.0", CanonicalKind.ENTITY,
                new NiemProvenance(NIEM_CORE, "nc:IncidentType", null), null,
                List.of(
                        niemField("incidentNumber", FieldType.STRING),
                        niemField("reportedDateTime", FieldType.DATE_TIME)),
                List.of());
    }

    private static CanonicalTypeDescriptor associationType() {
        return new CanonicalTypeDescriptor("PersonIncidentAssociation",
                "https://niemplatform.gov/canonical/extension/core/1.0", "1.0.0",
                CanonicalKind.ASSOCIATION, null,
                new ExtensionJustification("Pending verification of the NIEM association type."),
                List.of(new CanonicalFieldDescriptor("involvementCode", FieldType.CODE, true, false,
                        null, new ExtensionJustification("Agency role vocabulary."),
                        List.of("VICTIM", "SUSPECT", "WITNESS"), null)),
                List.of(
                        new CanonicalRoleDescriptor("person", "Person", null,
                                new ExtensionJustification("Role of the extension association.")),
                        new CanonicalRoleDescriptor("incident", "Incident", null,
                                new ExtensionJustification("Role of the extension association."))));
    }

    private static CanonicalFieldDescriptor niemField(String name, FieldType type) {
        return new CanonicalFieldDescriptor(name, type, false, false,
                new NiemProvenance(NIEM_CORE, null, "nc:" + name), null, List.of(), null);
    }

    private static Record person(String id, String surname, String given) {
        return Record.builder(personType().qualifiedName())
                .set("canonicalId", CanonicalId.of(id))
                .set("surName", surname)
                .set("givenName", given)
                .set("birthDate", LocalDate.of(1988, 3, 14))
                .build();
    }

    private static Record incident(String id, String number) {
        return Record.builder(incidentType().qualifiedName())
                .set("canonicalId", CanonicalId.of(id))
                .set("incidentNumber", number)
                .set("reportedDateTime", Instant.parse("2026-03-04T18:20:00Z"))
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

    private static CanonicalChangeSet slice() {
        return new CanonicalChangeSet("run-1", List.of(
                new TypedRecords(personType(), List.of(person("cluster-1", "DOE", "JANE"))),
                new TypedRecords(incidentType(), List.of(incident("INC/1", "2026-000114"))),
                new TypedRecords(associationType(),
                        List.of(association("PIA/1", "cluster-1", "INC/1", "VICTIM")))));
    }

    private long query(String cypher) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run(cypher).single().get(0).asLong());
        }
    }

    // --- criterion 4 -----------------------------------------------------

    @Test
    @DisplayName("criterion 4: the graph holds the expected nodes and the association edge")
    void projectsNodesAndEdge() {
        writer.apply(slice());

        assertThat(query("MATCH (p:Person) RETURN count(p)")).isEqualTo(1);
        assertThat(query("MATCH (i:Incident) RETURN count(i)")).isEqualTo(1);
        assertThat(query(
                "MATCH (:Person)-[r:PERSON_INCIDENT_ASSOCIATION]->(:Incident) RETURN count(r)"))
                .as("a NIEM association is already an edge; making it a node would be the "
                        + "flattening spec 4.6 rules out")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("entity fields become node properties, temporal ones staying temporal")
    void entityPropertiesAreProjected() {
        writer.apply(slice());

        try (Session session = driver.session()) {
            var node = session.executeRead(tx ->
                    tx.run("MATCH (p:Person {canonicalId: 'cluster-1'}) RETURN p").single().get("p"));

            assertThat(node.get("surName").asString()).isEqualTo("DOE");
            assertThat(node.get("givenName").asString()).isEqualTo("JANE");
            assertThat(node.get("birthDate").asLocalDate())
                    .as("a date stored as text cannot be range-queried by an investigator")
                    .isEqualTo(LocalDate.of(1988, 3, 14));
        }

        try (Session session = driver.session()) {
            var incidentNode = session.executeRead(tx -> tx.run(
                    "MATCH (i:Incident {canonicalId: 'INC/1'}) RETURN i").single().get("i"));

            assertThat(incidentNode.get("reportedDateTime").asOffsetDateTime().toInstant())
                    .as("the driver rejects java.time.Instant, so it is stored zoned at UTC -- "
                            + "the same moment, still temporal")
                    .isEqualTo(Instant.parse("2026-03-04T18:20:00Z"));
        }
    }

    @Test
    @DisplayName("the association's own fields become relationship properties")
    void associationFieldsBecomeEdgeProperties() {
        writer.apply(slice());

        try (Session session = driver.session()) {
            var edge = session.executeRead(tx -> tx.run(
                    "MATCH ()-[r:PERSON_INCIDENT_ASSOCIATION]->() RETURN r").single().get("r"));

            assertThat(edge.get("involvementCode").asString()).isEqualTo("VICTIM");
            assertThat(edge.get("canonicalId").asString()).isEqualTo("PIA/1");
        }
    }

    @Test
    @DisplayName("two source records for one human are one node, not two")
    void oneClusterIsOneNode() {
        writer.apply(new CanonicalChangeSet("run-1", List.of(
                new TypedRecords(personType(), List.of(
                        person("cluster-1", "DOE", "JANE"),
                        person("cluster-1", "DOE", "JANE"))))));

        assertThat(query("MATCH (p:Person) RETURN count(p)"))
                .as("identity resolution decided these are one person; the graph must agree")
                .isEqualTo(1);
    }

    @Nested
    @DisplayName("idempotence, which replay depends on")
    class Idempotence {

        @Test
        @DisplayName("applying the same change set twice leaves the same graph")
        void applyIsIdempotent() {
            writer.apply(slice());
            writer.apply(slice());

            assertThat(query("MATCH (p:Person) RETURN count(p)")).isEqualTo(1);
            assertThat(query("MATCH (i:Incident) RETURN count(i)")).isEqualTo(1);
            assertThat(query("MATCH ()-[r:PERSON_INCIDENT_ASSOCIATION]->() RETURN count(r)"))
                    .as("criterion 6 re-applies the same changes by design; a projection that "
                            + "double-counted would turn a correct replay into a wrong graph")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("re-applying updates properties rather than duplicating the node")
        void reapplyUpdatesInPlace() {
            writer.apply(slice());
            writer.apply(new CanonicalChangeSet("run-2", List.of(
                    new TypedRecords(personType(), List.of(person("cluster-1", "DOE", "JAYNE"))))));

            assertThat(query("MATCH (p:Person) RETURN count(p)")).isEqualTo(1);
            try (Session session = driver.session()) {
                String given = session.executeRead(tx -> tx.run(
                        "MATCH (p:Person {canonicalId: 'cluster-1'}) RETURN p.givenName")
                        .single().get(0).asString());
                assertThat(given).isEqualTo("JAYNE");
            }
        }
    }

    @Nested
    @DisplayName("rebuild replaces rather than merges")
    class Rebuild {

        @Test
        @DisplayName("a rebuild drops what silver no longer contains")
        void rebuildRemovesStaleRecords() {
            writer.apply(new CanonicalChangeSet("run-1", List.of(
                    new TypedRecords(personType(), List.of(
                            person("cluster-1", "DOE", "JANE"),
                            person("cluster-2", "RIVERA", "LUIS"))))));
            assertThat(query("MATCH (p:Person) RETURN count(p)")).isEqualTo(2);

            writer.rebuild(new CanonicalSnapshot(List.of(
                    new TypedRecords(personType(), List.of(person("cluster-1", "DOE", "JANE"))))));

            assertThat(query("MATCH (p:Person) RETURN count(p)"))
                    .as("a rebuild that merged would leave records silver no longer holds")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a full rebuild reproduces the whole graph from silver")
        void rebuildReproducesEverything() {
            writer.apply(slice());
            long personsBefore = query("MATCH (p:Person) RETURN count(p)");
            long edgesBefore = query("MATCH ()-[r:PERSON_INCIDENT_ASSOCIATION]->() RETURN count(r)");

            writer.rebuild(new CanonicalSnapshot(slice().changes()));

            assertThat(query("MATCH (p:Person) RETURN count(p)")).isEqualTo(personsBefore);
            assertThat(query("MATCH ()-[r:PERSON_INCIDENT_ASSOCIATION]->() RETURN count(r)"))
                    .isEqualTo(edgesBefore);
        }
    }

    @Test
    @DisplayName("counts are reportable, which is what makes a divergence event possible")
    void countsAreReportable() {
        writer.apply(slice());

        assertThat(writer.count(personType())).isEqualTo(1);
        assertThat(writer.count(incidentType())).isEqualTo(1);
        assertThat(writer.count(associationType())).isEqualTo(1);
    }

    @Test
    @DisplayName("an association whose endpoints are missing fails loudly rather than vanishing")
    void danglingAssociationFails() {
        assertThatThrownBy(() -> writer.apply(new CanonicalChangeSet("run-1", List.of(
                new TypedRecords(associationType(),
                        List.of(association("PIA/1", "missing", "also-missing", "VICTIM")))))))
                .isInstanceOf(ProjectionException.class)
                .hasMessageContaining("neither endpoint");

        assertThat(query("MATCH ()-[r:PERSON_INCIDENT_ASSOCIATION]->() RETURN count(r)"))
                .as("an association in silver with no edge in the graph is exactly the divergence "
                        + "4.7 exists to catch")
                .isZero();
    }

    @Test
    @DisplayName("an empty change set is a no-op, not an error")
    void emptyChangeSet() {
        writer.apply(new CanonicalChangeSet("run-1", List.of()));

        assertThat(query("MATCH (n) RETURN count(n)")).isZero();
    }
}
