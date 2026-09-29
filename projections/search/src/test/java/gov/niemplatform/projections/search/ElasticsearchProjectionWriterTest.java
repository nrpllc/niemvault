package gov.niemplatform.projections.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import gov.niemplatform.canonical.meta.TenantId;
import gov.niemplatform.projections.api.CanonicalChangeSet;
import gov.niemplatform.projections.api.CanonicalSnapshot;
import gov.niemplatform.projections.api.ProjectionContext;
import gov.niemplatform.projections.api.ProjectionDefinition;
import gov.niemplatform.projections.api.ProjectionException;
import gov.niemplatform.projections.api.ProjectionType;
import gov.niemplatform.projections.api.ProjectionWriter;
import gov.niemplatform.projections.api.TypedRecords;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The search projection against a real Elasticsearch.
 *
 * <p>Not a fake, for the reason the graph writer's tests give: what this writer can get wrong lives
 * in the cluster -- a strict mapping refusing a document, a bulk response that is 200 with failures
 * inside it, a painless script, an alias swap. A mock of the REST API would agree with whatever the
 * writer assumed.
 */
@Tag("docker")
@Testcontainers
class ElasticsearchProjectionWriterTest {

    private static final String NIEM_CORE =
            "https://docs.oasis-open.org/niemopen/ns/model/niem-core/6.0/";
    private static final String TENANT = "us.fl.leon-so";

    @Container
    private static final ElasticsearchContainer ELASTIC =
            new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.19.22")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<ProjectionWriter> opened = new ArrayList<>();

    /** Each test its own prefix, so claims and indices from one test cannot satisfy another. */
    private String prefix;

    @BeforeEach
    void setUp() {
        prefix = "t" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        opened.forEach(ProjectionWriter::close);
        http.close();
    }

    // --- model -----------------------------------------------------------

    private static CanonicalTypeDescriptor personType(CanonicalFieldDescriptor... extra) {
        List<CanonicalFieldDescriptor> fields = new ArrayList<>(List.of(
                niemField("surName", FieldType.STRING),
                niemField("givenName", FieldType.STRING),
                niemField("birthDate", FieldType.DATE)));
        fields.addAll(List.of(extra));
        return new CanonicalTypeDescriptor("Person",
                "https://niemplatform.gov/canonical/core/1.0", "1.0.0", CanonicalKind.ENTITY,
                new NiemProvenance(NIEM_CORE, "nc:PersonType", null), null, fields, List.of());
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
                "https://niemplatform.gov/canonical/core/1.0", "1.0.0",
                CanonicalKind.ASSOCIATION,
                new NiemProvenance(NIEM_CORE, "nc:ActivityPersonAssociationType", null), null,
                List.of(new CanonicalFieldDescriptor("involvementCode", FieldType.CODE, true, false,
                        null, new ExtensionJustification("Agency role vocabulary."),
                        List.of("VICTIM", "SUSPECT", "WITNESS"), null)),
                List.of(
                        new CanonicalRoleDescriptor("person", "Person",
                                new NiemProvenance(NIEM_CORE, null, "nc:Person"), null),
                        new CanonicalRoleDescriptor("incident", "Incident",
                                new NiemProvenance(NIEM_CORE, null, "nc:Activity"), null)));
    }

    private static List<CanonicalTypeDescriptor> model() {
        return List.of(personType(), incidentType(), associationType());
    }

    private static CanonicalFieldDescriptor niemField(String name, FieldType type) {
        return new CanonicalFieldDescriptor(name, type, false, false,
                new NiemProvenance(NIEM_CORE, null, "nc:" + name), null, List.of(), null);
    }

    private static Record person(String id, String surname, String given) {
        Record.Builder builder = Record.builder(personType().qualifiedName())
                .set("canonicalId", CanonicalId.of(id))
                .set("surName", surname)
                .set("birthDate", LocalDate.of(1988, 3, 14));
        if (given != null) {
            builder.set("givenName", given);
        }
        return builder.build();
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

    private static CanonicalChangeSet changes(List<Record> people, List<Record> incidents, List<Record> links) {
        return new CanonicalChangeSet("run-1", List.of(
                new TypedRecords(personType(), people),
                new TypedRecords(incidentType(), incidents),
                new TypedRecords(associationType(), links)));
    }

    // --- helpers ---------------------------------------------------------

    private ProjectionWriter open(String tenant, List<CanonicalTypeDescriptor> model) {
        ProjectionWriter writer = new ElasticsearchProjectionFactory().open(
                new ProjectionDefinition("test-search", "1.0.0", ProjectionType.SEARCH, Map.of(
                        "url", "http://" + ELASTIC.getHttpHostAddress(), "indexPrefix", prefix)),
                new ProjectionContext(TenantId.of(tenant), "leon-so-cad", "leon-cad-to-canonical",
                        "1.0.0", model, variable -> null));
        opened.add(writer);
        return writer;
    }

    private String alias(String type) {
        return SearchNaming.alias(prefix, TenantId.of(TENANT), type);
    }

    private JsonNode call(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://" + ELASTIC.getHttpHostAddress() + path))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode parsed = json.readTree(response.body());
        ((com.fasterxml.jackson.databind.node.ObjectNode) parsed).put("_httpStatus", response.statusCode());
        return parsed;
    }

    private JsonNode source(String type, String id) throws Exception {
        return call("GET", "/" + alias(type) + "/_doc/" + id, null).path("_source");
    }

    // --- apply -----------------------------------------------------------

    @Test
    void applyWritesEveryTypeAndLinksBothEndpoints() throws Exception {
        ProjectionWriter writer = open(TENANT, model());
        writer.apply(changes(
                List.of(person("p1", "Okonkwo", "Adaeze"), person("p2", "Lindqvist", "Tove")),
                List.of(incident("i1", "LCSO-26-0001")),
                List.of(association("a1", "p1", "i1", "SUSPECT"))));

        assertThat(writer.count(personType())).isEqualTo(2);
        assertThat(writer.count(incidentType())).isEqualTo(1);
        assertThat(writer.count(associationType())).isEqualTo(1);

        JsonNode person = source("Person", "p1");
        assertThat(person.path("links")).hasSize(1);
        assertThat(person.path("links").path(0).path("role").asText()).isEqualTo("person");
        assertThat(person.path("links").path(0).path("otherType").asText()).isEqualTo("Incident");
        assertThat(person.path("links").path(0).path("otherId").asText()).isEqualTo("i1");
        assertThat(person.path("links").path(0).path("fields").path("involvementCode").asText())
                .isEqualTo("SUSPECT");
        assertThat(person.path("niem").path("mapping").asText()).isEqualTo("leon-cad-to-canonical@1.0.0");
        assertThat(person.path("niem").path("sourceId").asText()).isEqualTo("leon-so-cad");

        JsonNode incident = source("Incident", "i1");
        assertThat(incident.path("links").path(0).path("role").asText()).isEqualTo("incident");
        assertThat(incident.path("links").path(0).path("otherId").asText()).isEqualTo("p1");

        JsonNode link = source("PersonIncidentAssociation", "a1");
        assertThat(link.path("person").asText()).isEqualTo("p1");
        assertThat(link.path("incident").asText()).isEqualTo("i1");
    }

    @Test
    void applyingTheSameChangesTwiceLeavesTheSameProjection() throws Exception {
        ProjectionWriter writer = open(TENANT, model());
        CanonicalChangeSet changes = changes(
                List.of(person("p1", "Okonkwo", "Adaeze")),
                List.of(incident("i1", "LCSO-26-0001")),
                List.of(association("a1", "p1", "i1", "SUSPECT")));

        writer.apply(changes);
        writer.apply(changes);

        assertThat(writer.count(personType())).isEqualTo(1);
        assertThat(writer.count(associationType())).isEqualTo(1);
        assertThat(source("Person", "p1").path("links")).hasSize(1);
        assertThat(source("Incident", "i1").path("links")).hasSize(1);
    }

    @Test
    void aFieldAbsentFromALaterRecordIsNotErased() throws Exception {
        ProjectionWriter writer = open(TENANT, model());
        writer.apply(changes(List.of(person("p1", "Okonkwo", "Adaeze")), List.of(), List.of()));
        writer.apply(changes(List.of(person("p1", "Okonkwo-Hale", null)), List.of(), List.of()));

        JsonNode person = source("Person", "p1");
        assertThat(person.path("surName").asText()).isEqualTo("Okonkwo-Hale");
        assertThat(person.path("givenName").asText()).isEqualTo("Adaeze");
    }

    @Test
    void anAssociationToAMissingEndpointFailsTheApply() {
        ProjectionWriter writer = open(TENANT, model());
        writer.apply(changes(List.of(), List.of(incident("i1", "LCSO-26-0001")), List.of()));

        assertThatThrownBy(() -> writer.apply(changes(
                List.of(), List.of(), List.of(association("a1", "nobody", "i1", "WITNESS")))))
                .isInstanceOf(ProjectionException.class)
                .hasMessageContaining("document_missing_exception")
                .hasMessageContaining("1 of 3 action(s) were refused")
                .satisfies(e -> assertThat(((ProjectionException) e).operation())
                        .isEqualTo(ProjectionException.Operation.APPLY));
    }

    @Test
    void aPersonIsFoundByNameThroughTheSingleSearchField() throws Exception {
        ProjectionWriter writer = open(TENANT, model());
        writer.apply(changes(
                List.of(person("p1", "Okonkwo", "Adaeze"), person("p2", "Lindqvist", "Tove")),
                List.of(), List.of()));

        JsonNode hits = call("POST", "/" + alias("Person") + "/_search",
                "{\"query\":{\"match\":{\"searchText\":\"okonkwo\"}}}").path("hits").path("hits");
        assertThat(hits).hasSize(1);
        assertThat(hits.path(0).path("_id").asText()).isEqualTo("p1");
    }

    // --- rebuild ---------------------------------------------------------

    @Test
    void rebuildReplacesTheProjectionBehindTheSameAlias() throws Exception {
        ProjectionWriter writer = open(TENANT, model());
        writer.apply(changes(
                List.of(person("p1", "Okonkwo", "Adaeze"), person("p2", "Lindqvist", "Tove")),
                List.of(), List.of()));
        List<String> before = new ArrayList<>();
        call("GET", "/_alias/" + alias("Person"), null).fieldNames().forEachRemaining(before::add);
        before.remove("_httpStatus");

        writer.rebuild(new CanonicalSnapshot(List.of(
                new TypedRecords(personType(), List.of(person("p1", "Okonkwo", "Adaeze"))),
                new TypedRecords(incidentType(), List.of(incident("i1", "LCSO-26-0001"))),
                new TypedRecords(associationType(), List.of(association("a1", "p1", "i1", "VICTIM"))))));

        assertThat(writer.count(personType())).isEqualTo(1);
        assertThat(call("GET", "/" + alias("Person") + "/_doc/p2", null).path("found").asBoolean()).isFalse();
        assertThat(source("Person", "p1").path("links").path(0).path("fields")
                .path("involvementCode").asText()).isEqualTo("VICTIM");

        List<String> after = new ArrayList<>();
        call("GET", "/_alias/" + alias("Person"), null).fieldNames().forEachRemaining(after::add);
        after.remove("_httpStatus");
        assertThat(after).hasSize(1).doesNotContainAnyElementsOf(before);
        assertThat(call("GET", "/" + before.getFirst(), null).path("_httpStatus").asInt()).isEqualTo(404);
    }

    // --- tenancy and model -------------------------------------------------

    @Test
    void aSecondTenantIsRefused() {
        open(TENANT, model());

        assertThatThrownBy(() -> open("us.fl.wakulla-so", model()))
                .isInstanceOf(ProjectionException.class)
                .hasMessageContaining("belong to '" + TENANT + "'")
                .satisfies(e -> assertThat(((ProjectionException) e).operation())
                        .isEqualTo(ProjectionException.Operation.INTEGRITY));
    }

    @Test
    void indicesThatHoldDataButNoClaimAreNotClaimed() throws Exception {
        call("PUT", "/" + prefix + "-stray/_doc/1?refresh=true", "{\"anything\":1}");

        assertThatThrownBy(() -> open(TENANT, model()))
                .isInstanceOf(ProjectionException.class)
                .hasMessageContaining("do not say whose they are")
                .satisfies(e -> assertThat(((ProjectionException) e).operation())
                        .isEqualTo(ProjectionException.Operation.INTEGRITY));
    }

    @Test
    void aFieldAddedToTheModelIsAddedToTheIndex() throws Exception {
        open(TENANT, model());
        ProjectionWriter evolved = open(TENANT, List.of(
                personType(niemField("middleName", FieldType.STRING)), incidentType(), associationType()));

        evolved.apply(new CanonicalChangeSet("run-2", List.of(new TypedRecords(
                personType(niemField("middleName", FieldType.STRING)),
                List.of(Record.builder(personType().qualifiedName())
                        .set("canonicalId", CanonicalId.of("p1"))
                        .set("surName", "Okonkwo")
                        .set("middleName", "Ngozi")
                        .build())))));

        assertThat(source("Person", "p1").path("middleName").asText()).isEqualTo("Ngozi");
    }

    @Test
    void aFieldWhoseTypeChangedIsRefusedRatherThanMistyped() {
        open(TENANT, model());
        CanonicalTypeDescriptor retyped = new CanonicalTypeDescriptor("Person",
                "https://niemplatform.gov/canonical/core/1.0", "2.0.0", CanonicalKind.ENTITY,
                new NiemProvenance(NIEM_CORE, "nc:PersonType", null), null,
                List.of(niemField("surName", FieldType.INTEGER)), List.of());

        assertThatThrownBy(() -> open(TENANT, List.of(retyped)))
                .isInstanceOf(ProjectionException.class)
                .hasMessageContaining("surName")
                .hasMessageContaining("needs a rebuild");
    }
}
