package gov.niemplatform.runtime.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.canonical.core.CoreCanonicalTypes;
import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.canonical.meta.CanonicalTypeResolver;
import gov.niemplatform.connectors.api.ConnectorConfig;
import gov.niemplatform.connectors.api.LandingService;
import gov.niemplatform.connectors.file.FileDropConnector;
import gov.niemplatform.contracts.ContractLoader;
import gov.niemplatform.contracts.HopContract;
import gov.niemplatform.contracts.QuarantineSink;
import gov.niemplatform.identity.api.InMemoryClusterIndex;
import gov.niemplatform.identity.api.IndexedResolutionProvider;
import gov.niemplatform.identity.internal.DeterministicResolutionProvider;
import gov.niemplatform.observability.ObservabilityEmitter;
import gov.niemplatform.projections.api.CanonicalChangeSet;
import gov.niemplatform.projections.api.ProjectionWriter;
import gov.niemplatform.projections.api.TypedRecords;
import gov.niemplatform.projections.graph.Neo4jProjectionWriter;
import gov.niemplatform.runtime.engine.MappingDefinition;
import gov.niemplatform.runtime.engine.MappingLoader;
import gov.niemplatform.runtime.engine.MappingPipeline;
import gov.niemplatform.storage.api.BronzeRange;
import gov.niemplatform.storage.api.CanonicalStore;
import gov.niemplatform.storage.api.RawEnvelope;
import gov.niemplatform.storage.iceberg.IcebergCanonicalStore;
import gov.niemplatform.storage.iceberg.IcebergCanonicalStoreConfig;
import gov.niemplatform.storage.parquet.ParquetBronzeStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * Acceptance criterion 6: deleting silver and gold and running replay reproduces both exactly.
 *
 * <p>The whole platform's central claim in one test. Bronze is the record of what a source sent and
 * everything downstream is derived from it, so everything downstream can be destroyed and rebuilt.
 * If this does not hold, the audit story does not hold either.
 *
 * <p>Run against real storage — Iceberg over an object store, and a real Neo4j — because the claim
 * is about what survives a round trip through actual persistence, and a test against fakes would
 * prove nothing about that.
 */
@Tag("docker")
@Testcontainers
class ReplayCriterionSixTest {

    private static final String SOURCE = "riverton-pd-cad";
    private static final String BUCKET = "niem-replay";
    private static final String KEY = "niemplatform";
    private static final String SECRET = "niemplatform-secret";
    private static final String NEO4J_PASSWORD = "niemplatform-test";
    private static final Instant NOW = Instant.parse("2026-03-09T18:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    @Container
    private static final MinIOContainer MINIO =
            new MinIOContainer("minio/minio:RELEASE.2024-11-07T00-52-20Z")
                    .withUserName(KEY).withPassword(SECRET);

    @Container
    private static final Neo4jContainer<?> NEO4J =
            new Neo4jContainer<>("neo4j:5.26").withAdminPassword(NEO4J_PASSWORD);

    @TempDir
    Path work;

    private ParquetBronzeStore bronze;
    private CanonicalStore silver;
    private Neo4jProjectionWriter graph;
    private Driver driver;
    private static int catalogSequence;

    @BeforeEach
    void setUp() throws IOException {
        createBucket();
        bronze = new ParquetBronzeStore(Files.createDirectories(work.resolve("bronze")), FIXED);
        silver = new IcebergCanonicalStore(new IcebergCanonicalStoreConfig(
                "silver",
                "jdbc:h2:mem:replay" + (catalogSequence++) + ";DB_CLOSE_DELAY=-1",
                "s3://" + BUCKET + "/silver-" + catalogSequence,
                MINIO.getS3URL(), KEY, SECRET, "us-east-1"));
        graph = new Neo4jProjectionWriter(NEO4J.getBoltUrl(), "neo4j", NEO4J_PASSWORD);
        driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("neo4j", NEO4J_PASSWORD));
        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run("MATCH (n) DETACH DELETE n").consume());
        }
    }

    @AfterEach
    void tearDown() {
        graph.close();
        driver.close();
        silver.close();
        bronze.close();
    }

    private static void createBucket() {
        try (S3Client client = S3Client.builder()
                .endpointOverride(java.net.URI.create(MINIO.getS3URL()))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(KEY, SECRET)))
                .build()) {
            client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException ignored) {
            // Warm container between tests.
        }
    }

    // --- content ---------------------------------------------------------

    private static InputStream resource(String path) {
        InputStream stream = ReplayCriterionSixTest.class.getResourceAsStream(path);
        if (stream == null) {
            throw new AssertionError("shipped artifact missing: " + path);
        }
        return stream;
    }

    private static MappingDefinition mapping() {
        try (InputStream artifact = resource("/mappings/cad-to-canonical-1.0.0.yaml")) {
            return new MappingLoader().load(artifact, "cad-to-canonical-1.0.0.yaml");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, HopContract> contracts() {
        ContractLoader loader = new ContractLoader(CanonicalTypeResolver.of(CoreCanonicalTypes.ALL));
        Map<String, HopContract> byHop = new LinkedHashMap<>();
        for (String name : List.of(
                "cad-incident-to-canonical-1.0.0.yaml",
                "cad-person-to-canonical-1.0.0.yaml",
                "cad-association-to-canonical-1.0.0.yaml")) {
            try (InputStream artifact = resource("/contracts/" + name)) {
                HopContract contract = loader.load(artifact, name);
                byHop.put(contract.hopId(), contract);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return byHop;
    }

    private static Map<String, CanonicalTypeDescriptor> canonicalTypes() {
        Map<String, CanonicalTypeDescriptor> types = new LinkedHashMap<>();
        CoreCanonicalTypes.ALL.forEach(type -> types.put(type.name(), type));
        return types;
    }

    /**
     * A pipeline with a fresh cluster index.
     *
     * <p>Fresh on purpose. Reusing the index would let a replay find clusters the original run
     * created and pass without ever re-deriving them, which would leave the harder half of
     * determinism untested. Identities have to come out the same from nothing.
     */
    private static MappingPipeline pipeline() {
        InMemoryClusterIndex index = new InMemoryClusterIndex();
        return new MappingPipeline(
                mapping(),
                contracts(),
                Map.of(DeterministicResolutionProvider.PROVIDER_ID, new IndexedResolutionProvider(
                        new DeterministicResolutionProvider(index), index)),
                canonicalTypes(),
                new QuarantineSink.InMemory(),
                ObservabilityEmitter.discarding());
    }

    // --- the original run ------------------------------------------------

    /** Lands the fixture and runs it through the pipeline into silver and gold, as a run would. */
    private void originalRun() throws IOException {
        Path drop = Files.createDirectories(work.resolve("drop"));
        try (InputStream fixture = resource("/fixtures/incidents.csv")) {
            Files.copy(fixture, drop.resolve("incidents.csv"));
        }

        ConnectorConfig config = ConnectorConfig.of(SOURCE, "file-drop-01", FileDropConnector.TYPE,
                Map.of("directory", drop.toString(), "filePattern", "*.csv", "skipHeaderLines", "1"));
        FileDropConnector connector = new FileDropConnector(FIXED);
        connector.configure(config);
        new LandingService(bronze, ObservabilityEmitter.discarding(), FIXED, 1_000)
                .land(connector, config, "run-1");

        MappingPipeline pipeline = pipeline();
        Map<String, List<Record>> produced = new LinkedHashMap<>();
        try (Stream<RawEnvelope> landed = bronze.read(SOURCE, BronzeRange.all())) {
            landed.forEach(envelope -> pipeline.process(envelope, "run-1").canonicalRecords()
                    .forEach(record -> produced
                            .computeIfAbsent(record.typeName(), type -> new ArrayList<>())
                            .add(record)));
        }

        List<TypedRecords> typed = new ArrayList<>();
        produced.forEach((typeName, records) -> {
            CanonicalTypeDescriptor descriptor = descriptorFor(typeName);
            silver.ensureTable(descriptor);
            silver.append(descriptor, records);
            typed.add(new TypedRecords(descriptor, records));
        });
        graph.apply(new CanonicalChangeSet("run-1", typed));
    }

    private static CanonicalTypeDescriptor descriptorFor(String qualifiedName) {
        return CoreCanonicalTypes.ALL.stream()
                .filter(type -> type.qualifiedName().equals(qualifiedName))
                .findFirst()
                .orElseThrow();
    }

    // --- reading state back ----------------------------------------------

    private Map<String, List<Record>> silverContents() {
        Map<String, List<Record>> contents = new LinkedHashMap<>();
        for (CanonicalTypeDescriptor descriptor : CoreCanonicalTypes.ALL) {
            try (Stream<Record> records = silver.read(descriptor)) {
                List<Record> read = records.toList();
                if (!read.isEmpty()) {
                    contents.put(descriptor.name(), read);
                }
            }
        }
        return contents;
    }

    private List<String> graphContents() {
        try (Session session = driver.session()) {
            List<String> rows = new ArrayList<>();
            session.executeRead(tx -> {
                tx.run("MATCH (n) RETURN labels(n)[0] AS label, n.canonicalId AS id ORDER BY label, id")
                        .forEachRemaining(row -> rows.add(
                                "node " + row.get("label").asString() + " " + row.get("id").asString()));
                return null;
            });
            session.executeRead(tx -> {
                tx.run("""
                        MATCH (a)-[r]->(b)
                        RETURN type(r) AS type, a.canonicalId AS from, b.canonicalId AS to,
                               r.involvementCode AS role
                        ORDER BY type, from, to
                        """).forEachRemaining(row -> rows.add(
                                "edge " + row.get("type").asString()
                                        + " " + row.get("from").asString()
                                        + " -> " + row.get("to").asString()
                                        + " " + row.get("role").asString()));
                return null;
            });
            return rows;
        }
    }

    // --- criterion 6 -----------------------------------------------------

    @Test
    @DisplayName("criterion 6: deleting silver and gold and replaying reproduces both exactly")
    void replayReproducesSilverAndGold() throws IOException {
        originalRun();

        Map<String, List<Record>> silverBefore = silverContents();
        List<String> graphBefore = graphContents();
        assertThat(silverBefore).isNotEmpty();
        assertThat(graphBefore).isNotEmpty();

        // Delete both derived zones outright. Bronze is untouched, and is the only thing that has
        // to survive for this to work.
        CoreCanonicalTypes.ALL.forEach(silver::drop);
        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run("MATCH (n) DETACH DELETE n").consume());
        }
        assertThat(silverContents()).isEmpty();
        assertThat(graphContents()).isEmpty();

        ReplayResult result = new ReplayDriver(bronze, silver, List.of(graph)).replay(
                ReplayRequest.all(SOURCE, "cad-to-canonical", "1.0.0", "replay-1"),
                pipeline());

        assertThat(result.envelopesRead()).isEqualTo(10);
        assertThat(result.recordsWritten()).isEqualTo(30);
        assertThat(result.clean()).isTrue();
        assertThat(result.projectionsRebuilt()).containsExactly("graph");

        assertThat(silverContents())
                .as("silver rebuilt from bronze must equal the silver that was deleted")
                .isEqualTo(silverBefore);
        assertThat(graphContents())
                .as("and so must every node and edge in the graph")
                .isEqualTo(graphBefore);
    }

    @Test
    @DisplayName("identities are re-derived, not remembered")
    void identitiesSurviveAFreshIndex() throws IOException {
        originalRun();
        Map<String, List<Record>> before = silverContents();

        CoreCanonicalTypes.ALL.forEach(silver::drop);
        // pipeline() builds a brand-new cluster index, so nothing carries over from the first run.
        new ReplayDriver(bronze, silver, List.of()).replay(
                ReplayRequest.all(SOURCE, "cad-to-canonical", "1.0.0", "replay-1"), pipeline());

        assertThat(silverContents().get("Person"))
                .as("cluster identities are derived from the resolution key that seeded them, so "
                        + "they come out the same from an empty index")
                .containsExactlyInAnyOrderElementsOf(before.get("Person"));
    }

    @Test
    @DisplayName("replaying twice changes nothing, because silver is rewritten and not appended")
    void replayIsIdempotent() throws IOException {
        originalRun();
        ReplayDriver replay = new ReplayDriver(bronze, silver, List.of(graph));
        ReplayRequest request = ReplayRequest.all(SOURCE, "cad-to-canonical", "1.0.0", "replay-1");

        replay.replay(request, pipeline());
        Map<String, List<Record>> afterFirst = silverContents();
        List<String> graphAfterFirst = graphContents();

        replay.replay(request, pipeline());

        assertThat(silverContents())
                .as("a replay that appended would double every record it reprocessed")
                .isEqualTo(afterFirst);
        assertThat(graphContents()).isEqualTo(graphAfterFirst);
    }

    @Test
    @DisplayName("a bronze range replays only the batches it names")
    void replayRespectsTheRange() throws IOException {
        originalRun();
        String firstBatch = bronze.batches(SOURCE).getFirst().batchId();

        CoreCanonicalTypes.ALL.forEach(silver::drop);
        ReplayResult result = new ReplayDriver(bronze, silver, List.of()).replay(
                new ReplayRequest(SOURCE, BronzeRange.batch(firstBatch),
                        "cad-to-canonical", "1.0.0", "replay-1"),
                pipeline());

        assertThat(result.envelopesRead()).isEqualTo(10);
    }

    @Test
    @DisplayName("replaying under a different mapping version is refused, not silently allowed")
    void refusesAMappingVersionMismatch() throws IOException {
        originalRun();

        assertThatThrownBy(() -> new ReplayDriver(bronze, silver, List.of()).replay(
                ReplayRequest.all(SOURCE, "cad-to-canonical", "2.0.0", "replay-1"), pipeline()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a replay of the original run");
    }

    @Test
    @DisplayName("bronze is untouched by a replay")
    void bronzeIsUntouched() throws IOException {
        originalRun();
        List<String> hashesBefore = bronzeHashes();

        CoreCanonicalTypes.ALL.forEach(silver::drop);
        new ReplayDriver(bronze, silver, List.of()).replay(
                ReplayRequest.all(SOURCE, "cad-to-canonical", "1.0.0", "replay-1"), pipeline());

        assertThat(bronzeHashes())
                .as("everything downstream is derived; the source record is not")
                .isEqualTo(hashesBefore);
    }

    private List<String> bronzeHashes() {
        try (Stream<RawEnvelope> envelopes = bronze.read(SOURCE, BronzeRange.all())) {
            return envelopes.map(envelope -> envelope.contentHash().toString()).toList();
        }
    }
}
