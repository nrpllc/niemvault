package gov.niemplatform.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.niemplatform.connectors.api.ConnectorConfig;
import gov.niemplatform.connectors.api.ConnectorRegistry;
import gov.niemplatform.content.SemanticVersion;
import gov.niemplatform.controlplane.advice.DeterministicAdvisor;
import gov.niemplatform.exchange.api.ExchangeRegistry;
import gov.niemplatform.projections.api.ProjectionRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * The field editor reading the designer's sample, and a new source's first mapping.
 *
 * <p>Both read an origin, so both carry the preview's obligations: nothing written, nothing
 * acknowledged, never the live consumer group.
 */
class FieldPreviewAndSkeletonTest {

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
        workspace = new MappingWorkspace(moduleRoot, SemanticVersion.parse("0.1.0"));
        designer = new PipelineDesigner(workspace, List.of());
    }

    private List<Path> files() throws IOException {
        try (Stream<Path> tree = Files.walk(work)) {
            return tree.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private Map<Path, String> snapshot() throws IOException {
        Map<Path, String> contents = new java.util.TreeMap<>();
        for (Path file : files()) {
            contents.put(file, Files.readString(file, StandardCharsets.ISO_8859_1));
        }
        return contents;
    }

    private ObjectNode fileDrop(String sourceId, String instance, Path drop) {
        ObjectNode origin = JSON.createObjectNode();
        origin.put("mode", "new").put("sourceId", sourceId).put("instance", instance).put("type", "file-drop");
        origin.putObject("settings").put("directory", drop.toString())
                .put("filePattern", "*.csv").put("skipHeaderLines", "1");
        return origin;
    }

    private Path leonDrop() throws IOException {
        Path drop = Files.createDirectories(work.resolve("drop"));
        Files.copy(moduleRoot.resolve("fixtures/leon-incidents.csv"), drop.resolve("leon-incidents.csv"));
        return drop;
    }

    private String leonYaml() {
        return workspace.source("leon-cad-to-canonical-1.0.0.yaml");
    }

    @Nested
    @DisplayName("field preview")
    class Fields {

        @Test
        @DisplayName("shows every step's input and output for real records, from the mapping as edited")
        void showsSteps() throws IOException {
            ObjectNode request = JSON.createObjectNode();
            request.put("yaml", leonYaml());
            request.put("hop", "map-incident");
            request.set("origin", fileDrop("leon-so-cad", "leon-cad-drop-test", leonDrop()));

            JsonNode result = new FieldPreview(designer).preview(request);

            assertThat(result.path("ran").asBoolean()).as(result.toPrettyString()).isTrue();
            JsonNode first = result.path("records").get(0);
            assertThat(first.path("admitted").asBoolean()).isTrue();
            JsonNode copy = first.path("steps").get(0);
            assertThat(copy.path("target").asText()).isEqualTo("incidentNumber");
            assertThat(copy.path("inputs").path("INC_NUM").asText()).isEqualTo("2026-000404");
            assertThat(copy.path("value").asText()).isEqualTo("2026-000404");
            assertThat(first.path("steps").get(1).path("value").asText()).isEqualTo("2026-03-04T12:21:00Z");
        }

        @Test
        @DisplayName("reflects an unsaved edit: the step shows what the edited option does")
        void unsavedEdit() throws IOException {
            ObjectNode request = JSON.createObjectNode();
            request.put("yaml", leonYaml().replace("zone: \"America/New_York\"", "zone: \"UTC\""));
            request.put("hop", "map-incident");
            request.set("origin", fileDrop("leon-so-cad", "leon-cad-drop-test", leonDrop()));

            JsonNode result = new FieldPreview(designer).preview(request);

            assertThat(result.path("records").get(0).path("steps").get(1).path("value").asText())
                    .isEqualTo("2026-03-04T07:21:00Z");
        }

        @Test
        @DisplayName("a record the gate refuses is flagged with the gate's reason, by shape")
        void refusedByShape() throws IOException {
            Path drop = Files.createDirectories(work.resolve("drifted"));
            Files.writeString(drop.resolve("x.csv"),
                    "INC_NUM,CALL_TYPE,RPT_DTTM,ADDR,BEAT,ROLE,NAME_FULL,DOB,SEX,DL_NUM\n"
                            + "2026-000404,MVA,2026-03-04T07:21,\"1 A St\",3A,RP,\"DOE, JANE\",12/05/1967,F,X1\n");
            ObjectNode request = JSON.createObjectNode();
            request.put("yaml", leonYaml());
            request.put("hop", "map-incident");
            request.set("origin", fileDrop("leon-so-cad", "drift", drop));

            JsonNode record = new FieldPreview(designer).preview(request).path("records").get(0);

            assertThat(record.path("admitted").asBoolean()).isFalse();
            assertThat(record.path("violations").get(0).asText())
                    .contains("RPT_DTTM").contains("shape=").doesNotContain("2026-03-04T07:21");
        }

        @Test
        @DisplayName("writes nothing, acknowledges nothing, and never reads as the live group")
        void writesNothing() throws IOException {
            AtomicInteger acknowledged = new AtomicInteger();
            List<ConnectorConfig> configured = new ArrayList<>();
            PipelineDesigner watched = new PipelineDesigner(workspace, List.of(),
                    () -> ConnectorRegistry.of(new PipelineDesignerTest.CountingConnector(acknowledged, configured)),
                    ProjectionRegistry.discover(), ExchangeRegistry::discover);
            ObjectNode request = JSON.createObjectNode();
            request.put("yaml", leonYaml());
            request.put("hop", "map-incident");
            ObjectNode origin = request.putObject("origin");
            origin.put("mode", "new").put("sourceId", "leon-so-cad").put("instance", "counted").put("type", "kafka");
            origin.putObject("settings").put("bootstrapServers", "localhost:19092")
                    .put("topic", "cad.leon.incidents").put("groupId", "niem-ingest-leon").put("retention", "retained");
            Map<Path, String> before = snapshot();

            JsonNode result = new FieldPreview(watched).preview(request);

            assertThat(result.path("ran").asBoolean()).as(result.toPrettyString()).isTrue();
            assertThat(acknowledged).hasValue(0);
            assertThat(configured).isNotEmpty().allSatisfy(config ->
                    assertThat(config.settings().get("groupId")).startsWith("niem-designer-field-preview-"));
            assertThat(snapshot()).isEqualTo(before);
        }

        @Test
        @DisplayName("without a pipeline, reads the module's own source definition for the mapping")
        void findsItsOwnOrigin() {
            ObjectNode request = JSON.createObjectNode();
            request.put("yaml", leonYaml());
            request.put("hop", "map-incident");

            JsonNode result = new FieldPreview(designer).preview(request);

            // The module's leon-so-cad definitions point at ./drop and a live broker; which one is
            // chosen is the module's first, and it is named rather than guessed at.
            assertThat(result.path("origin").asText()).startsWith("leon-so-cad/");
        }
    }

    @Nested
    @DisplayName("a first mapping for a new source")
    class Skeleton {

        private Path gadsden() throws IOException {
            Path drop = Files.createDirectories(work.resolve("gadsden"));
            Files.writeString(drop.resolve("calls.csv"), """
                    CallNo,Nature,ReceivedAt,Location,Zone,PartyRole,LastName,FirstName,BirthDate,Gender
                    GC-26-0001,THEFT,2026-09-28T08:15:00-04:00,"12 Pinewood Ct",Z1,VICTIM,ASHGROVE,ALDERIC,1970-02-11,M
                    GC-26-0001,THEFT,2026-09-28T08:15:00-04:00,"12 Pinewood Ct",Z1,SUSPECT,KILNBROOK,MERRIT,1991-07-30,M
                    GC-26-0002,DISTURB,2026-09-28T09:40:00-04:00,"400 Old Mill Rd",Z2,WITNESS,LARKSPIRE,NESSA,1985-12-01,F
                    """);
            return drop;
        }

        private JsonNode start(ObjectNode origin) {
            ObjectNode request = JSON.createObjectNode();
            request.set("origin", origin);
            return new MappingSkeleton(designer, new DeterministicAdvisor()).start(request);
        }

        @Test
        @DisplayName("takes its columns from the sampled header, and follows the module's hops")
        void columnsAndHops() throws IOException {
            JsonNode result = start(fileDrop("gadsden-so-cad", "gadsden-drop", gadsden()));

            assertThat(result.path("ran").asBoolean()).as(result.toPrettyString()).isTrue();
            assertThat(stream(result.path("columns")).map(c -> c.path("name").asText()))
                    .containsExactly("CallNo", "Nature", "ReceivedAt", "Location", "Zone", "PartyRole",
                            "LastName", "FirstName", "BirthDate", "Gender");
            String yaml = result.path("yaml").asText();
            assertThat(yaml).contains("source: gadsden-so-cad")
                    .contains("id: map-incident-gadsden-so-cad")
                    .contains("dependsOn: [map-person-gadsden-so-cad, map-incident-gadsden-so-cad]")
                    .contains("prefix: \"INC/GADSDEN-SO-CAD/\"");
        }

        @Test
        @DisplayName("loads, and says exactly what is still missing rather than pretending it is done")
        void reportsWhatIsMissing() throws IOException {
            JsonNode result = start(fileDrop("gadsden-so-cad", "gadsden-drop", gadsden()));

            MappingWorkspace.ValidationReport report = workspace.validate(result.path("yaml").asText());
            assertThat(report.definition()).as(String.join("\n", report.problems())).isNotNull();
            assertThat(stream(result.path("problems")).map(JsonNode::asText).toList())
                    .isEqualTo(report.problems());
            // Whatever the advisor could not propose with confidence is listed, not invented.
            if (!report.valid()) {
                assertThat(report.problems()).allSatisfy(problem -> assertThat(problem).isNotBlank());
            }
        }

        @Test
        @DisplayName("drafts a contract per hop, declaring every column, a pattern only where every value agreed")
        void draftsContracts() throws IOException {
            JsonNode result = start(fileDrop("gadsden-so-cad", "gadsden-drop", gadsden()));

            assertThat(stream(result.path("contractsWritten")).map(JsonNode::asText))
                    .containsExactly("contracts/gadsden-so-cad-incident-1.0.0.yaml",
                            "contracts/gadsden-so-cad-person-1.0.0.yaml",
                            "contracts/gadsden-so-cad-person-incident-1.0.0.yaml");
            String contract = Files.readString(moduleRoot.resolve("contracts/gadsden-so-cad-person-1.0.0.yaml"));
            assertThat(contract).contains("hop: map-person-gadsden-so-cad")
                    .contains("- name: BirthDate\n      type: string\n      pattern: \"^\\\\d{4}-\\\\d{2}-\\\\d{2}$\"")
                    // A name has a shape, and a pattern drafted from three surnames would refuse a fourth.
                    .doesNotContain("- name: LastName\n      type: string\n      pattern")
                    .contains("canonicalType: Person");
            assertThat(workspace.contracts()).anySatisfy(loaded ->
                    assertThat(loaded.hopId()).isEqualTo("map-person-gadsden-so-cad"));
        }

        @Test
        @DisplayName("writes no mapping: that is the author's to save")
        void writesNoMapping() throws IOException {
            start(fileDrop("gadsden-so-cad", "gadsden-drop", gadsden()));

            assertThat(workspace.mappings()).noneMatch(file -> file.name().startsWith("gadsden"));
        }

        @Test
        @DisplayName("never overwrites an existing contract, and starts nothing for a source already mapped")
        void neverOverwrites() throws IOException {
            JsonNode first = start(fileDrop("gadsden-so-cad", "gadsden-drop", gadsden()));
            Path contract = moduleRoot.resolve("contracts/gadsden-so-cad-person-1.0.0.yaml");
            Files.writeString(contract, Files.readString(contract) + "# reviewed\n");

            JsonNode second = start(fileDrop("gadsden-so-cad", "gadsden-drop", gadsden()));

            assertThat(first.path("contractsWritten")).hasSize(3);
            assertThat(second.path("contractsWritten")).isEmpty();
            assertThat(second.path("contractsKept")).hasSize(3);
            assertThat(Files.readString(contract)).endsWith("# reviewed\n");

            JsonNode leon = start(fileDrop("leon-so-cad", "leon-x", leonDrop()));
            assertThat(leon.path("ran").asBoolean()).isFalse();
            assertThat(leon.path("why").asText()).contains("already exists");
        }

        @Test
        @DisplayName("a pattern is a format, never text")
        void patterns() {
            assertThat(MappingSkeleton.patternFor(Map.of("####-##-##", 4))).contains("^\\d{4}-\\d{2}-\\d{2}$");
            assertThat(MappingSkeleton.patternFor(Map.of("##/##/####", 3, "####-##-##", 1))).isEmpty();
            assertThat(MappingSkeleton.patternFor(Map.of("AAAAAAAA", 3))).isEmpty();
            assertThat(MappingSkeleton.patternFor(Map.of("##:##_(#)", 2))).contains("^\\d{2}:\\d{2} \\(\\d{1}\\)$");
            assertThat(MappingSkeleton.split("a,\"b, c\",\"d \"\"e\"\"\",")).containsExactly("a", "b, c", "d \"e\"", "");
            assertThat(MappingSkeleton.columns(null, 3)).containsExactly("column1", "column2", "column3");
            assertThat(MappingSkeleton.columns(List.of("Call No", "1st", "Call No"), 3))
                    .containsExactly("Call_No", "column2", "column3");
        }
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        array.forEach(items::add);
        return items.stream();
    }
}
