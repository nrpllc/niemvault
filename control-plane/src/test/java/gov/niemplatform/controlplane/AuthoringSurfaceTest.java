package gov.niemplatform.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.niemplatform.content.SemanticVersion;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The mapping authoring surface, driven the way an author drives it.
 *
 * <p>Every test here works against the law enforcement module's real artifacts rather than a
 * hand-written fixture. An editor that only accepts mappings written for its own tests is an editor
 * that has never met the content it is supposed to edit.
 *
 * <p>They run against a <em>copy</em>, because saving writes a new version: a test that wrote into
 * the source tree would leave an untracked mapping behind for the next run to trip over.
 */
class AuthoringSurfaceTest {

    private static final SemanticVersion PLATFORM = SemanticVersion.parse("0.1.0");
    private static final String SHIPPED = "cad-to-canonical-1.0.0.yaml";

    @TempDir
    Path work;

    private Path moduleRoot;
    private MappingWorkspace workspace;

    @BeforeEach
    void copyTheModule() throws IOException {
        // Tests run with the project directory as the working directory.
        Path source = Path.of("").toAbsolutePath().getParent()
                .resolve("modules/law-enforcement/src/main/resources");
        assertThat(source).as("the law enforcement module must be where the tests expect it")
                .isDirectory();

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
    }

    private String shippedYaml() {
        return workspace.source(SHIPPED);
    }

    @Nested
    @DisplayName("Opening a module")
    class Opening {

        @Test
        @DisplayName("reports its identity and the platform range it declares")
        void readsTheManifest() {
            var manifest = workspace.manifest();
            assertThat(manifest.name()).isEqualTo("law-enforcement");
            assertThat(manifest.platformVersions().includes(PLATFORM)).isTrue();
        }

        @Test
        @DisplayName("refuses content this platform is too old to run")
        void refusesIncompatibleContent() {
            var stale = new MappingWorkspace(moduleRoot, SemanticVersion.parse("0.0.1"));
            assertThatThrownBy(stale::manifest).hasMessageContaining("law-enforcement");
        }

        @Test
        @DisplayName("lists the mappings on disk")
        void listsMappings() {
            assertThat(workspace.mappings())
                    .extracting(MappingWorkspace.MappingFile::qualifiedName)
                    .containsExactly("cad-to-canonical@1.0.0");
        }

        @Test
        @DisplayName("lists a mapping that will not load rather than hiding it")
        void listsBrokenMappings() throws IOException {
            // An author whose mapping is broken needs to open the very file they must fix. A list
            // that quietly omits it leaves them with no way in.
            Files.writeString(moduleRoot.resolve("mappings/half-written-0.1.0.yaml"),
                    "mapping: half-written\nhops: [\n", StandardCharsets.UTF_8);

            assertThat(workspace.mappings())
                    .filteredOn(mapping -> !mapping.loadable())
                    .extracting(MappingWorkspace.MappingFile::fileName)
                    .containsExactly("half-written-0.1.0.yaml");
        }
    }

    @Nested
    @DisplayName("Validating a draft")
    class Validating {

        @Test
        @DisplayName("accepts the mapping the module ships")
        void acceptsTheShippedMapping() {
            var report = workspace.validate(shippedYaml());
            assertThat(report.problems()).isEmpty();
            assertThat(report.valid()).isTrue();
            assertThat(report.definition().hops()).hasSize(3);
        }

        @Test
        @DisplayName("rejects a transform option the runtime would reject")
        void rejectsWhatTheRuntimeRejects() {
            // The whole claim of ADR 0021 is that this is the runtime's own answer, not a second
            // opinion. splitIndex takes an integer index, and "zero" is not one.
            var report = workspace.validate(shippedYaml().replace("index: \"0\"", "index: \"zero\""));

            assertThat(report.valid()).isFalse();
            assertThat(String.join("\n", report.problems())).contains("zero");
        }

        @Test
        @DisplayName("rejects an unknown transform type")
        void rejectsUnknownTransform() {
            var report = workspace.validate(shippedYaml().replace("type: copy", "type: sharpen"));

            assertThat(report.valid()).isFalse();
            assertThat(String.join("\n", report.problems())).contains("sharpen");
        }

        @Test
        @DisplayName("rejects a hop naming a contract the module does not carry")
        void rejectsAMissingContract() {
            // A mapping is not valid on its own. This draft loads perfectly and would fail at
            // deploy, which is the class of error an author should never discover there.
            var report = workspace.validate(
                    shippedYaml().replace("contract: cad-person-to-canonical", "contract: no-such-contract"));

            assertThat(report.valid()).isFalse();
            assertThat(String.join("\n", report.problems())).contains("no-such-contract");
        }

        @Test
        @DisplayName("returns problems rather than throwing, because half-typed is the normal state")
        void doesNotThrowOnGarbage() {
            // Validation runs on every keystroke. An exception per keystroke would be useless.
            var report = workspace.validate("mapping: [unclosed");

            assertThat(report.valid()).isFalse();
            assertThat(report.problems()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Saving")
    class Saving {

        @Test
        @DisplayName("writes a new version and leaves the reviewed mapping untouched")
        void savesAsANewVersion() {
            String original = shippedYaml();
            Path written = workspace.saveAsNewVersion(
                    original.replace("version: \"1.0.0\"\nsource:", "version: \"1.0.1\"\nsource:"),
                    "cad-to-canonical", "1.0.1");

            assertThat(written.getFileName()).hasToString("cad-to-canonical-1.0.1.yaml");
            assertThat(workspace.source(SHIPPED))
                    .as("the mapping an auditor may already have signed off is not rewritten")
                    .isEqualTo(original);
            assertThat(workspace.mappings())
                    .extracting(MappingWorkspace.MappingFile::qualifiedName)
                    .containsExactly("cad-to-canonical@1.0.1", "cad-to-canonical@1.0.0");
        }

        @Test
        @DisplayName("refuses to replace a version that already exists")
        void refusesToOverwrite() {
            assertThatThrownBy(() ->
                    workspace.saveAsNewVersion(shippedYaml(), "cad-to-canonical", "1.0.0"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bump the version");
        }

        @Test
        @DisplayName("suggests the next patch version, which is what most edits are")
        void suggestsTheNextVersion() {
            assertThat(workspace.nextVersion("1.0.0")).isEqualTo("1.0.1");
            assertThat(workspace.nextVersion("2.4.9")).isEqualTo("2.4.10");
        }

        @Test
        @DisplayName("refuses a file name that escapes the module")
        void refusesTraversal() {
            // The file name arrives from a browser. It does not get to choose which part of the
            // disk the editor reads.
            assertThatThrownBy(() -> workspace.source("../../../../etc/passwd"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outside the module");
        }
    }

    @Nested
    @DisplayName("Over HTTP")
    class OverHttp {

        private ControlPlaneServer server;
        private final HttpClient client = HttpClient.newHttpClient();
        private final ObjectMapper json = new ObjectMapper();

        @BeforeEach
        void start() {
            // Port 0: the OS picks a free one, so running the tests with the authoring surface
            // already open on 8088 does not fail on a bind.
            server = new ControlPlaneServer(workspace, 0);
            server.start();
        }

        @AfterEach
        void stop() {
            server.close();
        }

        private HttpResponse<String> get(String path) throws IOException, InterruptedException {
            return client.send(
                    HttpRequest.newBuilder(URI.create(server.url() + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private JsonNode getJson(String path) throws IOException, InterruptedException {
            return json.readTree(get(path).body());
        }

        private JsonNode post(String path, String body) throws IOException, InterruptedException {
            var response = client.send(
                    HttpRequest.newBuilder(URI.create(server.url() + path))
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return json.readTree(response.body());
        }

        @Test
        @DisplayName("binds to loopback only")
        void loopbackOnly() {
            // Phase 1 has no authentication beyond a stub (§8). An unauthenticated authoring tool
            // reachable from the network is a different thing entirely from one reachable here.
            assertThat(server.url()).startsWith("http://localhost:");
        }

        @Test
        @DisplayName("serves the page and its assets from the jar, and nothing else")
        void servesTheUi() throws Exception {
            for (String asset : new String[] {"/", "/app.css", "/app.js", "/canvas.js"}) {
                assertThat(get(asset).statusCode()).as(asset).isEqualTo(200);
            }
            assertThat(get("/../build.gradle.kts").statusCode()).isEqualTo(404);
        }

        @Test
        @DisplayName("offers the transform vocabulary the factory actually knows")
        void offersTheTransformVocabulary() throws Exception {
            // Read from the factory, so a transform added to the runtime reaches the editor without
            // anyone remembering to update a second list.
            assertThat(getJson("/api/transforms").get("types").toString())
                    .contains("splitIndex")
                    .contains("parseDate");
        }

        @Test
        @DisplayName("opens a mapping with its source, its shape and its rendered flow")
        void opensAMapping() throws Exception {
            var mapping = getJson("/api/mapping?file=" + SHIPPED);

            assertThat(mapping.get("name").asText()).isEqualTo("cad-to-canonical");
            assertThat(mapping.get("nextVersion").asText()).isEqualTo("1.0.1");
            assertThat(mapping.get("source").asText()).contains("hops:");
            assertThat(mapping.get("svg").asText()).startsWith("<svg");
            assertThat(mapping.get("hops")).hasSize(3);
        }

        @Test
        @DisplayName("redraws the flow on every validation, not only on save")
        void validationRedrawsTheFlow() throws Exception {
            var whole = post("/api/validate", shippedYaml());
            assertThat(whole.get("valid").asBoolean()).isTrue();
            assertThat(whole.get("svg").asText()).startsWith("<svg");

            // Delete the last hop. The drawing follows the edit, which is the point of editing
            // against a live preview rather than a diagram regenerated after the fact.
            String yaml = shippedYaml();
            var narrowed = post("/api/validate", yaml.substring(0, yaml.indexOf("  - id: map-person-incident")));

            assertThat(narrowed.get("mapping").get("hops")).hasSize(2);
            assertThat(narrowed.get("svg").asText()).doesNotContain("map-person-incident");
        }

        @Test
        @DisplayName("reports problems without a stack trace")
        void reportsProblems() throws Exception {
            var report = post("/api/validate", shippedYaml().replace("type: copy", "type: sharpen"));

            assertThat(report.get("valid").asBoolean()).isFalse();
            assertThat(report.get("problems").toString())
                    .contains("sharpen")
                    .doesNotContain("at gov.niemplatform");
        }

        @Test
        @DisplayName("applies a structural edit and returns the patched text with its validation")
        void appliesAnEdit() throws Exception {
            var result = post("/api/edit", json.createObjectNode()
                    .put("yaml", shippedYaml())
                    .put("op", "setField")
                    .put("hop", "map-incident")
                    .put("step", 0)
                    .put("key", "type")
                    .put("value", "trim")
                    .toString());

            assertThat(result.get("valid").asBoolean()).isTrue();
            assertThat(result.get("yaml").asText()).contains("- target: incidentNumber\n        type: trim");
            // The graph comes back with the text, so the form and the drawing never disagree.
            assertThat(result.get("svg").asText()).startsWith("<svg");
        }

        @Test
        @DisplayName("leaves the file's commentary intact when a form edits it")
        void editingKeepsComments() throws Exception {
            // The reason a form patches text instead of regenerating YAML. Losing this is losing
            // the reasoning behind every step in the mapping.
            var result = post("/api/edit", json.createObjectNode()
                    .put("yaml", shippedYaml())
                    .put("op", "setField")
                    .put("hop", "map-person")
                    .put("step", 1)
                    .put("key", "type")
                    .put("value", "lower")
                    .toString());

            long shipped = shippedYaml().lines().filter(line -> line.stripLeading().startsWith("#")).count();
            long edited = result.get("yaml").asText().lines()
                    .filter(line -> line.stripLeading().startsWith("#")).count();
            assertThat(edited).isEqualTo(shipped);
        }

        @Test
        @DisplayName("reports an edit that breaks the mapping instead of refusing to make it")
        void reportsABreakingEdit() throws Exception {
            // The author gets the edit they asked for and the problem it caused. Refusing the edit
            // would make an intermediate state unreachable -- retyping a step before rewiring it.
            var result = post("/api/edit", json.createObjectNode()
                    .put("yaml", shippedYaml())
                    .put("op", "setField")
                    .put("hop", "map-incident")
                    .put("step", 0)
                    .put("key", "type")
                    .put("value", "sharpen")
                    .toString());

            assertThat(result.get("yaml").asText()).contains("type: sharpen");
            assertThat(result.get("valid").asBoolean()).isFalse();
            assertThat(result.get("problems").toString()).contains("sharpen");
        }

        @Test
        @DisplayName("rejects an edit the patcher will not make, changing nothing")
        void rejectsAnImpossibleEdit() throws Exception {
            var response = client.send(
                    HttpRequest.newBuilder(URI.create(server.url() + "/api/edit"))
                            .POST(HttpRequest.BodyPublishers.ofString(json.createObjectNode()
                                    .put("yaml", shippedYaml())
                                    .put("op", "setOption")
                                    .put("hop", "map-incident")
                                    .put("step", 0)
                                    .put("key", "pattern")
                                    .put("value", "x")
                                    .toString(), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json.readTree(response.body()).get("error").asText())
                    .contains("edit it as text");
        }

        @Test
        @DisplayName("refuses to save a draft that would not load")
        void refusesToSaveABrokenDraft() throws Exception {
            // A mapping on disk that does not load is a deployment that fails at start-up, and the
            // editor is the last place it can be caught cheaply.
            var result = post("/api/save", json.createObjectNode()
                    .put("yaml", shippedYaml().replace("type: copy", "type: sharpen")).toString());

            assertThat(result.get("saved").asBoolean()).isFalse();
            assertThat(workspace.mappings()).hasSize(1);
        }

        @Test
        @DisplayName("saves a valid draft as a new version")
        void savesAValidDraft() throws Exception {
            var result = post("/api/save", json.createObjectNode()
                    .put("yaml", shippedYaml().replace("version: \"1.0.0\"\nsource:", "version: \"1.1.0\"\nsource:"))
                    .toString());

            assertThat(result.get("saved").asBoolean()).isTrue();
            assertThat(result.get("qualifiedName").asText()).isEqualTo("cad-to-canonical@1.1.0");
            assertThat(moduleRoot.resolve("mappings/cad-to-canonical-1.1.0.yaml")).exists();
        }
    }

    @Nested
    @DisplayName("The rendered flow")
    class Rendering {

        @Test
        @DisplayName("draws the source, every hop, and the canonical type each one produces")
        void drawsTheWholeFlow() {
            String svg = DagSvg.render(workspace.load(SHIPPED));

            assertThat(svg).startsWith("<svg").endsWith("</svg>");
            assertThat(svg)
                    .contains("map-incident")
                    .contains("map-person")
                    .contains("map-person-incident")
                    .contains("Incident")
                    .contains("Person");
        }

        @Test
        @DisplayName("names the hop behind every node it draws, so the graph can be clicked into")
        void carriesHopIds() {
            String svg = DagSvg.render(workspace.load(SHIPPED));

            // The source belongs to no single hop; every other node does.
            assertThat(svg)
                    .contains("data-hop=\"map-incident\"")
                    .contains("data-hop=\"map-person\"")
                    .contains("data-hop=\"map-person-incident\"");
        }

        @Test
        @DisplayName("escapes what it draws, because a mapping is author-supplied text")
        void escapesText() {
            // Placed at the front of the emitted type on purpose: node titles are truncated to
            // fit the box, and a marker at the end would be cut off before it proved anything.
            var report = workspace.validate(
                    shippedYaml().replace("emits: \"source:", "emits: \"<script>:"));

            assertThat(report.definition()).isNotNull();
            assertThat(DagSvg.render(report.definition()))
                    .doesNotContain("<script>")
                    .contains("&lt;script&gt;");
        }
    }
}
