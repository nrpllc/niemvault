package gov.niemplatform.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.connectors.api.ConnectorConfig;
import gov.niemplatform.connectors.api.ConnectorRegistry;
import gov.niemplatform.connectors.api.ConnectorType;
import gov.niemplatform.connectors.api.HealthStatus;
import gov.niemplatform.connectors.api.InteractionMode;
import gov.niemplatform.connectors.api.RetentionPosture;
import gov.niemplatform.connectors.api.SourceConnector;
import gov.niemplatform.connectors.api.SourceHandle;
import gov.niemplatform.exchange.api.AssembledDocument;
import gov.niemplatform.exchange.api.ExchangeDefinition;
import gov.niemplatform.exchange.api.ExchangeHealth;
import gov.niemplatform.exchange.api.ExchangeRegistry;
import gov.niemplatform.exchange.api.ExchangeType;
import gov.niemplatform.exchange.api.ExchangeWriter;
import gov.niemplatform.exchange.api.SubmissionReceipt;
import gov.niemplatform.projections.api.ProjectionContext;
import gov.niemplatform.projections.api.ProjectionDefinition;
import gov.niemplatform.projections.api.ProjectionFactory;
import gov.niemplatform.projections.api.ProjectionRegistry;
import gov.niemplatform.projections.api.ProjectionType;
import gov.niemplatform.projections.api.ProjectionWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR 0037: a pipeline names its parts, and resolves them against the module the law enforcement
 * content actually ships plus whatever a deployment supplies.
 */
class PipelineTest {

    /** The real module, so a renamed source or mapping breaks this test rather than a deployment. */
    static final Path MODULE = Path.of("../modules/law-enforcement/src/main/resources");

    static final String LIVE = """
            pipeline: leon-cad-live
            version: "1.0.0"
            description: Leon County SO CAD, live
            origin:
              source: leon-so-cad
              instance: leon-cad-kafka-1
            processors:
              mapping: leon-cad-to-canonical@1.0.0
            destinations:
              projections: [demo-ods]
              exchanges: [fdle-cch-incidents-leon]
            """;

    // --- the artifact ------------------------------------------------------------------

    @Nested
    @DisplayName("the artifact")
    class Artifact {

        @Test
        @DisplayName("reads its parts by name")
        void reads() {
            PipelineDefinition pipeline = PipelineDefinition.parse(LIVE, null);

            assertThat(pipeline.qualifiedName()).isEqualTo("leon-cad-live@1.0.0");
            assertThat(pipeline.originInstance()).isEqualTo("leon-cad-kafka-1");
            assertThat(pipeline.mapping()).isEqualTo("leon-cad-to-canonical@1.0.0");
            assertThat(pipeline.projections()).containsExactly("demo-ods");
            assertThat(pipeline.fileName()).isEqualTo("leon-cad-live-1.0.0.yaml");
        }

        @Test
        @DisplayName("is strict: every problem, a misspelling among them, at once")
        void strict() {
            assertThatThrownBy(() -> PipelineDefinition.parse("""
                    pipeline: Leon_Live
                    version: "1.0"
                    origin: { source: leon-so-cad }
                    processors: { mapping: leon-cad-to-canonical }
                    destinatons: { projections: [a] }
                    """, null))
                    .isInstanceOf(PipelineDefinitionException.class)
                    .satisfies(e -> assertThat(((PipelineDefinitionException) e).problems())
                            .anySatisfy(p -> assertThat(p).contains("kebab-case"))
                            .anySatisfy(p -> assertThat(p).contains("semver"))
                            .anySatisfy(p -> assertThat(p).contains("origin.instance"))
                            .anySatisfy(p -> assertThat(p).contains("pinned"))
                            .anySatisfy(p -> assertThat(p).contains("unrecognised key 'destinatons'")));
        }

        @Test
        @DisplayName("a destination named twice is refused")
        void duplicate() {
            assertThatThrownBy(() -> PipelineDefinition.parse(
                    LIVE.replace("[demo-ods]", "[demo-ods, demo-ods]"), null))
                    .hasMessageContaining("names 'demo-ods' twice");
        }

        @Test
        @DisplayName("what the designer writes reads back as the same pipeline")
        void roundTrip() {
            PipelineDefinition pipeline = PipelineDefinition.parse(LIVE, null);
            PipelineDefinition again = PipelineDefinition.parse(pipeline.toYaml("Written by a test"), null);

            assertThat(again).isEqualTo(pipeline);
        }
    }

    // --- resolution --------------------------------------------------------------------

    @Nested
    @DisplayName("resolution")
    class Resolution {

        @TempDir
        Path deployment;

        private PipelineResolver resolver(List<Path> deploymentDirectories) {
            return new PipelineResolver(ArtifactCatalog.of(MODULE, deploymentDirectories),
                    ConnectorRegistry.of(new StubConnector("kafka"), new StubConnector("file-drop")),
                    ProjectionRegistry.of(List.of(new StubFactory(ProjectionType.ODS))),
                    ExchangeRegistry.of(new StubExchange()));
        }

        private void deploymentProjection(String name, String version) throws IOException {
            Files.writeString(deployment.resolve(name + "-" + version + ".yaml"), """
                    projection: %s
                    version: "%s"
                    type: ods
                    settings:
                      jdbcUrl: jdbc:postgresql://localhost:15432/niem
                    """.formatted(name, version));
        }

        @Test
        @DisplayName("resolves the module's source, mapping and exchange")
        void module() throws IOException {
            deploymentProjection("demo-ods", "1.0.0");
            var resolution = resolver(List.of(deployment))
                    .resolve(PipelineDefinition.parse(LIVE, null), PipelineResolver.Strictness.RUN);

            assertThat(resolution.problems()).isEmpty();
            assertThat(resolution.sourceFile()).get().extracting(p -> p.getFileName().toString())
                    .isEqualTo("leon-so-cad-kafka.yaml");
            assertThat(resolution.mapping()).get().extracting(m -> m.qualifiedName())
                    .isEqualTo("leon-cad-to-canonical@1.0.0");
            assertThat(resolution.exchanges()).singleElement()
                    .extracting(ExchangeDefinition::exchangeName).isEqualTo("fdle-cch-incidents-leon");
            assertThat(resolution.projections()).singleElement()
                    .extracting(ProjectionDefinition::projectionName).isEqualTo("demo-ods");
        }

        @Test
        @DisplayName("a destination the module does not ship is a problem to run, a note to author")
        void deploymentSupplied() {
            var pipeline = PipelineDefinition.parse(LIVE, null);

            assertThat(resolver(List.of()).resolve(pipeline, PipelineResolver.Strictness.RUN).problems())
                    .singleElement().satisfies(p -> {
                        assertThat(p.stage()).isEqualTo("projection:demo-ods");
                        assertThat(p.detail()).contains("--artifacts");
                    });

            var authoring = resolver(List.of()).resolve(pipeline, PipelineResolver.Strictness.AUTHORING);
            assertThat(authoring.runnable()).isTrue();
            assertThat(authoring.notes()).anySatisfy(n -> assertThat(n).contains("a deployment supplies it"));
        }

        @Test
        @DisplayName("a deployment's source definition shadows the module's, which is how 'where' changes")
        void shadows() throws IOException {
            deploymentProjection("demo-ods", "1.0.0");
            Files.writeString(deployment.resolve("leon-kafka-here.yaml"), """
                    sourceId: leon-so-cad
                    connectorInstanceId: leon-cad-kafka-1
                    type: kafka
                    settings:
                      bootstrapServers: localhost:29092
                      topic: cad.leon.incidents
                      groupId: niem-ingest-leon
                      retention: retained
                    """);
            var resolution = resolver(List.of(deployment))
                    .resolve(PipelineDefinition.parse(LIVE, null), PipelineResolver.Strictness.RUN);

            assertThat(resolution.problems()).isEmpty();
            assertThat(resolution.source()).get()
                    .satisfies(s -> assertThat(s.settings()).containsEntry("bootstrapServers", "localhost:29092"));
            assertThat(resolution.notes()).anySatisfy(n -> assertThat(n).contains("deployment's own"));
        }

        @Test
        @DisplayName("a mapping written against another source is refused, as run refuses it")
        void wrongSource() {
            var resolution = resolver(List.of()).resolve(PipelineDefinition.parse(
                    LIVE.replace("leon-cad-to-canonical@1.0.0", "cad-to-canonical@1.0.0"), null),
                    PipelineResolver.Strictness.AUTHORING);

            assertThat(resolution.problems()).anySatisfy(p -> {
                assertThat(p.stage()).isEqualTo("mapping");
                assertThat(p.detail()).contains("riverton-pd-cad");
            });
        }

        @Test
        @DisplayName("an origin nobody defined names what was looked for")
        void missingOrigin() {
            var resolution = resolver(List.of()).resolve(PipelineDefinition.parse(
                    LIVE.replace("leon-cad-kafka-1", "leon-cad-carrier-pigeon-1"), null),
                    PipelineResolver.Strictness.AUTHORING);

            assertThat(resolution.problems()).anySatisfy(p ->
                    assertThat(p.toString()).startsWith("origin: no source definition 'leon-so-cad/leon-cad-carrier-pigeon-1'"));
        }

        @Test
        @DisplayName("a bare name that matches two versions must be pinned")
        void ambiguous() throws IOException {
            deploymentProjection("demo-ods", "1.0.0");
            deploymentProjection("demo-ods", "1.1.0");
            var resolution = resolver(List.of(deployment))
                    .resolve(PipelineDefinition.parse(LIVE, null), PipelineResolver.Strictness.RUN);

            assertThat(resolution.problems()).singleElement()
                    .satisfies(p -> assertThat(p.detail()).contains("name one with @version"));
            assertThat(resolver(List.of(deployment)).resolve(PipelineDefinition.parse(
                    LIVE.replace("[demo-ods]", "[demo-ods@1.1.0]"), null),
                    PipelineResolver.Strictness.RUN).problems()).isEmpty();
        }

        @Test
        @DisplayName("two exchanges is refused: a run submits through one")
        void oneExchange() {
            var resolution = resolver(List.of()).resolve(PipelineDefinition.parse(
                    LIVE.replace("[fdle-cch-incidents-leon]",
                            "[fdle-cch-incidents-leon, fdle-cch-arrest]"), null),
                    PipelineResolver.Strictness.AUTHORING);

            assertThat(resolution.problems()).anySatisfy(p -> assertThat(p.detail()).contains("one per repository"));
        }

        @Test
        @DisplayName("a transport this deployment does not carry is named, with what it does carry")
        void missingTransport() {
            var resolver = new PipelineResolver(ArtifactCatalog.of(MODULE, List.of()),
                    ConnectorRegistry.of(new StubConnector("file-drop")),
                    ProjectionRegistry.of(List.of()), ExchangeRegistry.of(new StubExchange()));

            assertThat(resolver.resolve(PipelineDefinition.parse(LIVE, null),
                    PipelineResolver.Strictness.AUTHORING).problems())
                    .anySatisfy(p -> assertThat(p.detail()).contains("'kafka' is not on this deployment's classpath"));
        }
    }

    // --- stubs: resolution asks registries what they carry, and nothing else --------------

    record StubConnector(String id) implements SourceConnector {
        public ConnectorType type() { return ConnectorType.of(id); }
        public InteractionMode interactionMode() { return InteractionMode.POLL; }
        public RetentionPosture retention() { return RetentionPosture.RETAINED; }
        public void configure(ConnectorConfig config) { }
        public SourceHandle open() { throw new UnsupportedOperationException(); }
        public HealthStatus health() { throw new UnsupportedOperationException(); }
        public void close() { }
    }

    record StubFactory(ProjectionType type) implements ProjectionFactory {
        public ProjectionWriter open(ProjectionDefinition definition, ProjectionContext context) {
            throw new UnsupportedOperationException("resolution never opens a projection");
        }
    }

    static final class StubExchange implements ExchangeWriter {
        public ExchangeType type() { return ExchangeType.of("cch-http"); }
        public void configure(ExchangeDefinition definition) { }
        public List<SubmissionReceipt> submit(List<AssembledDocument> documents) {
            throw new UnsupportedOperationException();
        }
        public ExchangeHealth health() { throw new UnsupportedOperationException(); }
        public void close() { }
    }
}
