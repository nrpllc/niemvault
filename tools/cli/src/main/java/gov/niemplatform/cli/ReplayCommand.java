package gov.niemplatform.cli;

import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.contracts.QuarantineSink;
import gov.niemplatform.identity.api.InMemoryClusterIndex;
import gov.niemplatform.identity.api.IndexedResolutionProvider;
import gov.niemplatform.identity.internal.DeterministicResolutionProvider;
import gov.niemplatform.observability.LoggingObservabilityEmitter;
import gov.niemplatform.observability.ObservabilityEmitter;
import gov.niemplatform.observability.RecordingObservabilityEmitter;
import gov.niemplatform.projections.api.ProjectionWriter;
import gov.niemplatform.projections.graph.Neo4jProjectionWriter;
import gov.niemplatform.runtime.engine.MappingPipeline;
import gov.niemplatform.runtime.replay.ReplayDriver;
import gov.niemplatform.runtime.replay.ReplayRequest;
import gov.niemplatform.runtime.replay.ReplayResult;
import gov.niemplatform.storage.api.BronzeRange;
import gov.niemplatform.storage.api.CanonicalStore;
import gov.niemplatform.storage.iceberg.IcebergCanonicalStore;
import gov.niemplatform.storage.iceberg.IcebergCanonicalStoreConfig;
import gov.niemplatform.storage.parquet.ParquetBronzeStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Rebuilds silver, and optionally the graph, from bronze alone.
 *
 * <p>Spec §5. This is the command that makes the platform's central claim checkable by an operator
 * rather than only by a test: bronze is what the source actually sent, and everything downstream is
 * a function of bronze and a pinned mapping version. If that is true, silver can be deleted and
 * reproduced exactly. If it is not, this is where it shows.
 *
 * <h2>It destroys silver on purpose</h2>
 *
 * <p>Replay <em>drops and rewrites</em> the canonical tables for every type it produces; appending
 * would double every record it reprocessed. That is legitimate for silver, which is derived, and
 * would not be for bronze, which is why {@code CanonicalStore} carries a drop and
 * {@code BronzeStore} does not.
 *
 * <p>Because it destroys, it says what it is about to destroy first, and {@code --dry-run} stops
 * after saying it.
 *
 * <h2>Secrets do not come from the command line</h2>
 *
 * <p>The object store secret and the graph password are read from the environment. A password
 * passed as an option is written to shell history and is visible in the process list to every user
 * on the machine, which is not a defensible way to handle credentials for a system holding criminal
 * justice data.
 */
@Command(
        name = "replay",
        mixinStandardHelpOptions = true,
        description = "Rebuild silver (and optionally the graph) from bronze under a pinned mapping.")
final class ReplayCommand implements Callable<Integer> {

    /** Environment variable holding the graph password. */
    static final String NEO4J_PASSWORD = "NIEM_NEO4J_PASSWORD";

    @Option(names = {"-m", "--module"}, required = true,
            description = "Domain module directory containing mappings/ and contracts/.")
    Path moduleDirectory;

    @Option(names = "--tenant", required = true,
            description = "The agency this data belongs to, e.g. co.riverton.pd. Required even on a "
                    + "deployment that hosts one: cluster identities are tenant-scoped, so an "
                    + "implied tenant is how a shared deployment becomes commingled (ADR 0025).")
    String tenant;


    @Option(names = "--mapping", required = true,
            description = "Mapping artifact to replay under. Its version is pinned into the request.")
    Path mappingFile;

    @Option(names = "--bronze", required = true, description = "Bronze storage root.")
    Path bronzeRoot;

    @Option(names = "--from", description = "First bronze batch id to replay. Omitted: from the start.")
    String fromBatchId;

    @Option(names = "--to", description = "Last bronze batch id to replay. Omitted: to the end.")
    String toBatchId;

    @Option(names = "--batch", description = "Replay exactly one bronze batch id.")
    String batchId;

    @Option(names = "--silver-catalog-uri", required = true,
            description = "JDBC URI of the Iceberg catalogue, e.g. jdbc:h2:file:/data/catalog.")
    String silverCatalogUri;

    @Option(names = "--silver-warehouse", required = true,
            description = "Iceberg warehouse location, e.g. s3://silver/warehouse.")
    String silverWarehouse;

    @Option(names = "--silver-catalog-name", defaultValue = "niem",
            description = "Catalogue name. Default: ${DEFAULT-VALUE}")
    String silverCatalogName;

    @Option(names = "--s3-endpoint",
            description = "Object store endpoint. Omitted: the AWS default for the region.")
    String s3Endpoint;

    @Option(names = "--s3-access-key-id",
            description = "Object store access key id. The secret is read from $" + SilverOptions.S3_SECRET + ".")
    String s3AccessKeyId;

    @Option(names = "--s3-region", defaultValue = "us-east-1",
            description = "Object store region. Default: ${DEFAULT-VALUE}")
    String s3Region;

    @Option(names = "--neo4j-uri",
            description = "Graph to rebuild. Omitted: silver only, and the command says so.")
    String neo4jUri;

    @Option(names = "--neo4j-user", defaultValue = "neo4j",
            description = "Graph user. The password is read from $" + NEO4J_PASSWORD + ".")
    String neo4jUser;

    @Option(names = "--run-id", defaultValue = "replay",
            description = "Run identifier carried on every event. Default: ${DEFAULT-VALUE}")
    String runId;

    @Option(names = "--dry-run",
            description = "Report what would be dropped and rebuilt, then stop without writing.")
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
        if (batchId != null && (fromBatchId != null || toBatchId != null)) {
            System.err.println("--batch names a single batch; it cannot be combined with --from or --to.");
            return 1;
        }

        ArtifactSet artifacts = ArtifactSet.load(mappingFile, moduleDirectory.resolve("contracts"));
        List<String> crossReference = artifacts.crossReferenceProblems();
        if (!crossReference.isEmpty()) {
            System.err.println("Content is not coherent; run `niem validate` for detail:");
            crossReference.forEach(problem -> System.err.println("  " + problem));
            return 1;
        }

        String secret = System.getenv(SilverOptions.S3_SECRET);
        if (s3AccessKeyId != null && secret == null) {
            System.err.println("--s3-access-key-id was given but $" + SilverOptions.S3_SECRET + " is not set.");
            return 1;
        }
        if (neo4jUri != null && System.getenv(NEO4J_PASSWORD) == null) {
            System.err.println("--neo4j-uri was given but $" + NEO4J_PASSWORD + " is not set.");
            return 1;
        }

        var definition = artifacts.mapping();
        ReplayRequest request = new ReplayRequest(
                definition.sourceId(), range(), definition.name(), definition.version(), runId);

        System.out.printf("Replaying %s under %s%n", request.sourceId(), request.qualifiedMapping());
        System.out.printf("  bronze  %s  (%s)%n", bronzeRoot, describe(request.range()));
        System.out.printf("  silver  %s in %s%n", silverWarehouse, silverCatalogUri);
        System.out.println(neo4jUri == null
                ? "  graph   not rebuilt -- no --neo4j-uri given, so silver only"
                : "  graph   " + neo4jUri);

        IcebergCanonicalStoreConfig silverConfig = new IcebergCanonicalStoreConfig(
                silverCatalogName, silverCatalogUri, silverWarehouse,
                s3Endpoint, s3AccessKeyId, secret, s3Region);

        List<ProjectionWriter> projections = new ArrayList<>();
        try (ParquetBronzeStore bronze = new ParquetBronzeStore(bronzeRoot, gov.niemplatform.canonical.meta.TenantId.of(tenant));
                CanonicalStore silver = new IcebergCanonicalStore(silverConfig)) {

            List<CanonicalTypeDescriptor> produced = artifacts.canonicalTypes().values().stream()
                    .filter(descriptor -> definition.hops().stream().anyMatch(hop ->
                            hop.identity().entityType().equals(descriptor.name())))
                    .toList();

            // Said before anything is touched, because the next step deletes it.
            System.out.println();
            System.out.println("Silver tables this replay will drop and rewrite:");
            for (CanonicalTypeDescriptor descriptor : produced) {
                System.out.printf("  %-28s %d record(s) now%n", descriptor.name(), existing(silver, descriptor));
            }

            if (dryRun) {
                System.out.println();
                System.out.println("--dry-run: nothing was written.");
                return 0;
            }

            if (neo4jUri != null) {
                projections.add(new Neo4jProjectionWriter(
                        neo4jUri, neo4jUser, System.getenv(NEO4J_PASSWORD)));
            }

            RecordingObservabilityEmitter recorder = new RecordingObservabilityEmitter();
            ObservabilityEmitter emitter =
                    ObservabilityEmitter.composite(recorder, new LoggingObservabilityEmitter());
            QuarantineSink.InMemory quarantine = new QuarantineSink.InMemory();

            // A fresh cluster index, deliberately. Replaying against the index the original run
            // built would let resolution inherit an answer instead of recomputing it, and the
            // comparison would prove less than it appears to.
            InMemoryClusterIndex clusterIndex = new InMemoryClusterIndex(gov.niemplatform.canonical.meta.TenantId.of(tenant));
            MappingPipeline pipeline = new MappingPipeline(
                    definition,
                    artifacts.contractsByHop(),
                    Map.of(DeterministicResolutionProvider.PROVIDER_ID, new IndexedResolutionProvider(
                            new DeterministicResolutionProvider(clusterIndex), clusterIndex)),
                    artifacts.canonicalTypes(),
                    quarantine,
                    emitter);

            ReplayResult result = new ReplayDriver(bronze, silver, projections).replay(request, pipeline);
            report(result);

            // Same convention as `run`: quarantined records are not a failure -- §4.2 is explicit
            // that bad data must not halt the pipeline -- but a scheduler should be able to tell a
            // clean replay from one that dropped records on the floor.
            return result.clean() ? 0 : 2;
        } finally {
            projections.forEach(ProjectionWriter::close);
        }
    }

    private BronzeRange range() {
        if (batchId != null) {
            return BronzeRange.batch(batchId);
        }
        if (fromBatchId == null && toBatchId == null) {
            return BronzeRange.all();
        }
        return BronzeRange.between(fromBatchId, toBatchId);
    }

    private static String describe(BronzeRange range) {
        if (range.fromBatchId() == null && range.toBatchId() == null) {
            return "every batch";
        }
        if (range.fromBatchId() != null && range.fromBatchId().equals(range.toBatchId())) {
            return "batch " + range.fromBatchId();
        }
        return "%s .. %s".formatted(
                range.fromBatchId() == null ? "start" : range.fromBatchId(),
                range.toBatchId() == null ? "end" : range.toBatchId());
    }

    /** What is in a table now, or nothing if it has never been written. */
    private static String existing(CanonicalStore silver, CanonicalTypeDescriptor descriptor) {
        try {
            return Long.toString(silver.count(descriptor));
        } catch (RuntimeException absent) {
            // A table that does not exist yet is the normal state on a first replay, not an error.
            return "0 (no table yet)";
        }
    }

    private static void report(ReplayResult result) {
        System.out.println();
        System.out.printf("Read %d envelope(s) from bronze.%n", result.envelopesRead());
        result.commits().forEach((type, commit) ->
                System.out.printf("  %-28s %d record(s) written, snapshot %d%n",
                        type, commit.recordCount(), commit.snapshotId()));
        System.out.printf("Total canonical records: %d%n", result.recordsWritten());

        if (result.projectionsRebuilt().isEmpty()) {
            System.out.println("No projections rebuilt.");
        } else {
            System.out.printf("Projections rebuilt: %s%n", String.join(", ", result.projectionsRebuilt()));
        }

        if (result.clean()) {
            System.out.println("No records quarantined.");
        } else {
            System.out.printf("%d record(s) quarantined; silver holds only what passed its contracts.%n",
                    result.quarantined());
        }
    }
}
