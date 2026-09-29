package gov.niemplatform.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.niemplatform.connectors.api.ConnectorConfig;
import gov.niemplatform.connectors.api.ConnectorRegistry;
import gov.niemplatform.connectors.api.ConnectorType;
import gov.niemplatform.connectors.api.HealthStatus;
import gov.niemplatform.connectors.api.InteractionMode;
import gov.niemplatform.connectors.api.RetentionPosture;
import gov.niemplatform.connectors.api.SourceConnector;
import gov.niemplatform.connectors.api.SourceDefinition;
import gov.niemplatform.connectors.api.SourceHandle;
import gov.niemplatform.content.SemanticVersion;
import gov.niemplatform.exchange.api.ExchangeRegistry;
import gov.niemplatform.projections.api.ProjectionRegistry;
import gov.niemplatform.storage.api.RawEnvelope;
import gov.niemplatform.storage.api.SourceOffset;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pipeline designer (ADR 0037), against a copy of the module the law enforcement content
 * actually ships -- a copy, because saving writes into it.
 */
class PipelineDesignerTest {

    private static final SemanticVersion PLATFORM = SemanticVersion.parse("0.1.0");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path work;

    private Path moduleRoot;
    private MappingWorkspace workspace;
    private PipelineDesigner designer;

    @BeforeEach
    void copyTheModule() throws IOException {
        Path source = Path.of("").toAbsolutePath().getParent()
                .resolve("modules/law-enforcement/src/main/resources");
        moduleRoot = work.resolve("module");
        try (Stream<Path> tree = Files.walk(source)) {
            for (Path path : tree.toList()) {
                Path target = moduleRoot.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
        workspace = new MappingWorkspace(moduleRoot, PLATFORM);
        designer = new PipelineDesigner(workspace, List.of());
    }

    /** A drop directory holding a copy of the Leon fixture: previews read this, never the module. */
    private Path drop() throws IOException {
        Path drop = Files.createDirectories(work.resolve("drop"));
        Files.copy(moduleRoot.resolve("fixtures/leon-incidents.csv"), drop.resolve("leon-incidents.csv"));
        return drop;
    }

    private ObjectNode draft(String version) {
        ObjectNode draft = JSON.createObjectNode();
        draft.putObject("pipeline").put("name", "leon-cad-test").put("version", version)
                .put("description", "Leon County SO CAD fixture");
        draft.putObject("origin").put("mode", "existing")
                .put("sourceId", "leon-so-cad").put("instance", "leon-cad-kafka-1");
        draft.put("mapping", "leon-cad-to-canonical@1.0.0");
        draft.putArray("destinations").addObject()
                .put("kind", "exchange").put("mode", "existing").put("ref", "fdle-cch-incidents-leon");
        return draft;
    }

    private void newFileDropOrigin(ObjectNode draft, Path drop) {
        ObjectNode origin = draft.putObject("origin");
        origin.put("mode", "new").put("sourceId", "leon-so-cad").put("instance", "leon-cad-drop-test")
                .put("type", "file-drop");
        origin.putObject("settings").put("directory", drop.toString())
                .put("filePattern", "*.csv").put("skipHeaderLines", "1");
    }

    private List<Path> files() throws IOException {
        try (Stream<Path> tree = Files.walk(work)) {
            return tree.filter(Files::isRegularFile).sorted().toList();
        }
    }

    // --- palette -----------------------------------------------------------------------

    @Test
    @DisplayName("the palette is what the deployment carries, described by each component")
    void palette() {
        JsonNode palette = designer.palette();

        assertThat(palette.path("origins").findValuesAsText("type")).contains("file-drop", "kafka");
        JsonNode kafka = stream(palette.path("origins")).filter(o -> o.path("type").asText().equals("kafka"))
                .findFirst().orElseThrow();
        assertThat(kafka.path("settings").findValuesAsText("key"))
                .contains("bootstrapServers", "topic", "groupId", "retention");
        assertThat(stream(kafka.path("settings")).filter(s -> s.path("key").asText().equals("saslJaasConfig"))
                .findFirst().orElseThrow().path("sensitivity").asText()).isEqualTo("secret_value");

        assertThat(palette.path("processors").get(0).path("mappings").findValuesAsText("ref"))
                .contains("leon-cad-to-canonical@1.0.0");
        assertThat(palette.path("destinations").findValuesAsText("type"))
                .contains("ods", "search", "graph", "cch-http");
        assertThat(palette.path("sources").findValuesAsText("name")).contains("leon-so-cad/leon-cad-kafka-1");
        assertThat(palette.path("exchanges").findValuesAsText("name")).contains("fdle-cch-incidents-leon");
    }

    // --- validation --------------------------------------------------------------------

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("a pipeline of existing parts that fit is valid")
        void valid() {
            JsonNode result = designer.validate(draft("1.0.0"), false);

            assertThat(result.path("problems").size()).as(result.toPrettyString()).isZero();
            assertThat(result.path("valid").asBoolean()).isTrue();
            assertThat(result.path("yaml").asText()).contains("mapping: leon-cad-to-canonical@1.0.0");
            assertThat(result.path("origin").path("type").asText()).isEqualTo("kafka");
        }

        @Test
        @DisplayName("problems are attributed to the stage they belong to")
        void attributed() {
            ObjectNode draft = draft("1.0.0");
            draft.put("mapping", "cad-to-canonical@1.0.0");

            JsonNode problems = designer.validate(draft, false).path("problems");
            assertThat(problems.path("mapping").get(0).asText()).contains("riverton-pd-cad");
            assertThat(problems.path("exchange:fdle-cch-incidents-leon")).isEmpty();
        }

        @Test
        @DisplayName("a new projection is checked against what its store declares, without opening it")
        void newProjection() {
            ObjectNode draft = draft("1.0.0");
            draft.withArray("destinations").addObject().put("kind", "projection").put("mode", "new")
                    .put("name", "laptop-ods").put("version", "1.0.0").put("type", "ods")
                    .putObject("settings").put("user", "niem").put("passwordEnv", "not a variable!");

            JsonNode problems = designer.validate(draft, false).path("problems").path("projection:laptop-ods");
            assertThat(problems.toString()).contains("jdbcUrl").contains("is required")
                    .contains("passwordEnv").doesNotContain("not a variable!");
        }

        @Test
        @DisplayName("a secret a transport reads literally is refused, and never echoed")
        void literalSecret() throws IOException {
            ObjectNode draft = draft("1.0.0");
            ObjectNode origin = draft.putObject("origin");
            origin.put("mode", "new").put("sourceId", "leon-so-cad").put("instance", "leon-sasl")
                    .put("type", "kafka");
            origin.putObject("settings").put("bootstrapServers", "localhost:1").put("topic", "t")
                    .put("groupId", "g").put("retention", "retained")
                    .put("saslJaasConfig", "password=\"hunter2\"");

            JsonNode problems = designer.validate(draft, false).path("problems").path("origin");
            assertThat(problems.toString()).contains("ADR 0015").doesNotContain("hunter2");
        }

        @Test
        @DisplayName("a new transport is configured by the connector itself, which catches what a form cannot")
        void connectorIsTheAuthority() {
            ObjectNode draft = draft("1.0.0");
            ObjectNode origin = draft.putObject("origin");
            origin.put("mode", "new").put("sourceId", "leon-so-cad").put("instance", "leon-kafka-x")
                    .put("type", "kafka");
            origin.putObject("settings").put("bootstrapServers", "localhost:1").put("topic", "t")
                    .put("groupId", "g").put("retention", "retained").put("maxRecords", "0");

            // maxRecords 0 is a whole number, so the form accepts it; the connector does not.
            assertThat(designer.validate(draft, false).path("problems").path("origin").toString())
                    .contains("maxRecords");
        }
    }

    // --- save --------------------------------------------------------------------------

    @Nested
    @DisplayName("saving")
    class Saving {

        @Test
        @DisplayName("writes the new definitions and the pipeline, and never over an existing version")
        void neverOverwrites() throws IOException {
            ObjectNode draft = draft("1.0.0");
            newFileDropOrigin(draft, drop());

            JsonNode first = designer.save(draft);
            assertThat(first.path("saved").asBoolean()).as(first.toPrettyString()).isTrue();
            assertThat(first.path("written").findValuesAsText("")).isEmpty();
            assertThat(moduleRoot.resolve("pipelines/leon-cad-test-1.0.0.yaml")).exists();
            assertThat(moduleRoot.resolve("sources/leon-cad-drop-test.yaml")).exists();
            String written = Files.readString(moduleRoot.resolve("pipelines/leon-cad-test-1.0.0.yaml"));

            ObjectNode again = draft("1.0.0");
            JsonNode second = designer.save(again);
            assertThat(second.path("saved").asBoolean()).isFalse();
            assertThat(second.path("problems").path("pipeline").toString()).contains("already exists");
            assertThat(Files.readString(moduleRoot.resolve("pipelines/leon-cad-test-1.0.0.yaml")))
                    .isEqualTo(written);

            // And it opens as the next version, naming the parts rather than copying them.
            JsonNode opened = designer.pipeline("leon-cad-test-1.0.0.yaml");
            assertThat(opened.path("draft").path("pipeline").path("version").asText()).isEqualTo("1.0.1");
            assertThat(opened.path("draft").path("origin").path("mode").asText()).isEqualTo("existing");
            assertThat(designer.pipelines().path("pipelines").findValuesAsText("name")).contains("leon-cad-test");
        }

        @Test
        @DisplayName("an invalid draft writes nothing at all")
        void invalidWritesNothing() throws IOException {
            List<Path> before = files();
            ObjectNode draft = draft("1.0.0");
            draft.put("mapping", "cad-to-canonical@1.0.0");

            assertThat(designer.save(draft).path("saved").asBoolean()).isFalse();
            assertThat(files()).isEqualTo(before);
        }
    }

    // --- preview -----------------------------------------------------------------------

    @Nested
    @DisplayName("preview")
    class Preview {

        @Test
        @DisplayName("maps real records through every stage and writes nothing anywhere")
        void writesNothing() throws IOException {
            ObjectNode draft = draft("1.0.0");
            newFileDropOrigin(draft, drop());
            List<Path> before = files();

            JsonNode result = designer.preview(draft, 10);

            assertThat(result.path("ran").asBoolean()).as(result.toPrettyString()).isTrue();
            assertThat(result.path("read").asInt()).isEqualTo(10);
            assertThat(result.path("stages").path("origin").path("out").asInt()).isEqualTo(10);
            assertThat(result.path("stages").path("mapping").path("out").asInt()).isEqualTo(30);
            assertThat(result.path("stages").path("destinations")
                    .path("exchange:fdle-cch-incidents-leon").path("in").asInt()).isPositive();
            assertThat(result.path("completeness").path("balanced").asBoolean()).isTrue();
            assertThat(result.path("samples").path("mapped").size()).isPositive();
            assertThat(files()).as("preview must not write, move or claim anything").isEqualTo(before);
        }

        @Test
        @DisplayName("a record the contract refuses is shown held, with why -- its shape, not its value")
        void heldByShape() throws IOException {
            Path drop = drop();
            // Named to sort first, so it is inside the preview's limit.
            Files.writeString(drop.resolve("a-drift.csv"), "INC_NUM,CALL_TYPE,RPT_DTTM,ADDR,BEAT,ROLE,NAME_FULL,DOB,SEX,DL_NUM\n"
                    + "2026-099001,BURG,2026/09/29 13:05,\"77 Drift Ln\",3A,VICT,\"MALFORMED, TEST\",1979-12-01,F,Z0000001\n",
                    StandardCharsets.UTF_8);
            ObjectNode draft = draft("1.0.0");
            newFileDropOrigin(draft, drop);

            JsonNode result = designer.preview(draft, 50);

            JsonNode held = result.path("samples").path("held");
            assertThat(held.size()).isEqualTo(1);
            assertThat(held.get(0).path("violations").toString()).contains("DOB").contains("shape=####-##-##")
                    .doesNotContain("1979-12-01");
            assertThat(result.path("stages").path("gate").path("held").asInt()).isEqualTo(1);
            assertThat(result.path("completeness").path("balanced").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("never acknowledges: nothing is committed, archived, deleted or advanced")
        void neverAcknowledges() throws IOException {
            AtomicInteger acknowledged = new AtomicInteger();
            List<ConnectorConfig> configured = new ArrayList<>();
            PipelineDesigner watched = new PipelineDesigner(workspace, List.of(),
                    () -> ConnectorRegistry.of(new CountingConnector(acknowledged, configured)),
                    ProjectionRegistry.discover(), ExchangeRegistry::discover);

            ObjectNode draft = draft("1.0.0");
            ObjectNode origin = draft.putObject("origin");
            origin.put("mode", "new").put("sourceId", "leon-so-cad").put("instance", "leon-counted")
                    .put("type", "kafka");
            origin.putObject("settings").put("bootstrapServers", "localhost:19092")
                    .put("topic", "cad.leon.incidents").put("groupId", "niem-ingest-leon")
                    .put("retention", "retained");

            JsonNode result = watched.preview(draft, 5);

            assertThat(result.path("ran").asBoolean()).as(result.toPrettyString()).isTrue();
            assertThat(acknowledged).hasValue(0);
            // And never reads on the live ingest's group: that group's position is not the designer's.
            // (Validation configures a connector with the draft's own settings to check them, but
            // configuring connects to nothing; only what is opened reads.)
            assertThat(configured).isNotEmpty().allSatisfy(config -> {
                assertThat(config.settings().get("groupId")).startsWith("niem-designer-")
                        .isNotEqualTo("niem-ingest-leon");
            });
            assertThat(result.path("consumerGroup").asText()).startsWith("niem-designer-preview-");
        }

        @Test
        @DisplayName("before a mapping is chosen, the origin alone is read and shown raw")
        void originOnly() throws IOException {
            ObjectNode draft = draft("1.0.0");
            newFileDropOrigin(draft, drop());
            draft.remove("mapping");

            JsonNode result = designer.preview(draft, 3);

            assertThat(result.path("ran").asBoolean()).as(result.toPrettyString()).isTrue();
            assertThat(result.path("originOnly").asBoolean()).isTrue();
            assertThat(result.path("samples").path("origin").size()).isEqualTo(3);
            assertThat(result.path("samples").path("origin").get(0).asText()).contains("2026-000404");
        }

        @Test
        @DisplayName("a Kafka origin previews on its own group, for a handful of records")
        void previewGroup() {
            SourceDefinition live = new SourceDefinition("leon-so-cad", "leon-cad-kafka-1",
                    ConnectorType.of("kafka"), Map.of("bootstrapServers", "localhost:19092",
                            "topic", "cad.leon.incidents", "groupId", "niem-ingest-leon",
                            "retention", "retained", "maxRecords", "500", "idleMillis", "20000"), null);

            SourceDefinition preview = PipelineDesigner.forPreview(live, "preview", 7);

            assertThat(preview.settings().get("groupId")).startsWith("niem-designer-preview-");
            assertThat(preview.settings()).containsEntry("maxRecords", "7").containsEntry("idleMillis", "3000")
                    .containsEntry("topic", "cad.leon.incidents");
            assertThat(live.settings().get("groupId")).isEqualTo("niem-ingest-leon");
        }
    }

    // --- over HTTP ---------------------------------------------------------------------

    @Test
    @DisplayName("a write is only accepted as a JSON POST, which another page cannot send without asking")
    void jsonOnly() throws Exception {
        try (ControlPlaneServer server = new ControlPlaneServer(workspace, 0)) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> plain = client.send(HttpRequest.newBuilder(
                    URI.create(server.url() + "/api/pipeline/save"))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(draft("1.0.0").toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(plain.statusCode()).isEqualTo(400);
            assertThat(plain.body()).contains("application/json");
            assertThat(moduleRoot.resolve("pipelines/leon-cad-test-1.0.0.yaml")).doesNotExist();

            HttpResponse<String> palette = client.send(HttpRequest.newBuilder(
                    URI.create(server.url() + "/api/palette")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(palette.statusCode()).isEqualTo(200);
            assertThat(palette.body()).contains("\"origins\"");
        }
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        array.forEach(items::add);
        return items.stream();
    }

    /** A Kafka-typed connector that serves five records and counts acknowledgements. */
    private static final class CountingConnector implements SourceConnector {
        private final AtomicInteger acknowledged;
        private final List<ConnectorConfig> configured;
        private ConnectorConfig config;

        CountingConnector(AtomicInteger acknowledged, List<ConnectorConfig> configured) {
            this.acknowledged = acknowledged;
            this.configured = configured;
        }

        public ConnectorType type() { return ConnectorType.of("kafka"); }
        public InteractionMode interactionMode() { return InteractionMode.PUSH; }
        public RetentionPosture retention() { return RetentionPosture.RETAINED; }

        public void configure(ConnectorConfig connectorConfig) {
            this.config = connectorConfig;
        }

        public SourceHandle open() {
            configured.add(config);
            List<RawEnvelope> envelopes = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                envelopes.add(new RawEnvelope(config.sourceId(), config.connectorInstanceId(),
                        Instant.now(), Instant.now(),
                        ("2026-00040" + i + ",MVA,2026/03/04 07:21,\"5902 Quarrystone Rd\",3A,RP,"
                                + "\"STONECARROW, RASMINE\",12/05/1967,F,U758-7384").getBytes(StandardCharsets.UTF_8),
                        SourceOffset.of("p0@" + i)));
            }
            return new SourceHandle() {
                public Stream<RawEnvelope> envelopes() { return envelopes.stream(); }
                public void acknowledge() { acknowledged.incrementAndGet(); }
                public void close() { }
            };
        }

        public HealthStatus health() { return HealthStatus.healthy(Instant.now()); }
        public void close() { }
    }
}
