package gov.niemplatform.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.connectors.api.ConnectorConfig;
import gov.niemplatform.connectors.api.LandingService;
import gov.niemplatform.connectors.file.FileDropConnector;
import gov.niemplatform.contracts.FileQuarantineSink;
import gov.niemplatform.contracts.QuarantineSink;
import gov.niemplatform.contracts.QuarantinedRecord;
import gov.niemplatform.identity.api.InMemoryClusterIndex;
import gov.niemplatform.identity.api.IndexedResolutionProvider;
import gov.niemplatform.identity.internal.DeterministicResolutionProvider;
import gov.niemplatform.observability.ContractViolation;
import gov.niemplatform.observability.LoggingObservabilityEmitter;
import gov.niemplatform.observability.ObservabilityEmitter;
import gov.niemplatform.observability.RecordingObservabilityEmitter;
import gov.niemplatform.runtime.engine.ExecutionMode;
import gov.niemplatform.runtime.engine.FlinkMappingJob;
import gov.niemplatform.runtime.engine.JobOptions;
import gov.niemplatform.runtime.engine.MappingPipeline;
import gov.niemplatform.runtime.engine.MappingPipelineFactory;
import gov.niemplatform.storage.api.BronzeRange;
import gov.niemplatform.storage.api.RawEnvelope;
import gov.niemplatform.storage.parquet.ParquetBronzeStore;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Lands a source and maps it to canonical.
 *
 * <p>Bronze to silver, minus the silver store. Canonical records are written as JSON lines rather
 * than to a canonical table, because the canonical store is not built yet (ADR 0005). That is
 * stated in the command's own output rather than left for an operator to infer -- a run that
 * looked like it had populated silver would be worse than one that refused to run.
 *
 * <p>Useful as it stands: this is how an operator checks a mapping against a real export before
 * anything is wired to a table, which is exactly the review step spec §4.3 describes as the
 * valuable half of source onboarding.
 */
@Command(
        name = "run",
        mixinStandardHelpOptions = true,
        description = "Land a file drop into bronze and map it to canonical.")
final class RunCommand implements Callable<Integer> {

    @Option(names = {"-m", "--module"}, required = true,
            description = "Domain module directory containing mappings/ and contracts/.")
    Path moduleDirectory;

    @Option(names = "--mapping", required = true, description = "Mapping artifact to run.")
    Path mappingFile;

    @Option(names = "--drop", required = true, description = "Directory the source drops files into.")
    Path dropDirectory;

    @Option(names = "--bronze", required = true, description = "Bronze storage root.")
    Path bronzeRoot;

    @Option(names = "--pattern", defaultValue = "*.csv",
            description = "Glob of files to pick up. Default: ${DEFAULT-VALUE}")
    String filePattern;

    @Option(names = "--skip-header-lines", defaultValue = "1",
            description = "Header rows to skip per file. Default: ${DEFAULT-VALUE}")
    int skipHeaderLines;

    @Option(names = "--out",
            description = "Write canonical records here as JSON lines. Omitted: counts only.")
    Path canonicalOut;

    @Option(names = "--quarantine-out",
            description = "Write quarantined records here as JSON lines, values intact.")
    Path quarantineOut;

    @Option(names = "--run-id", defaultValue = "cli",
            description = "Run identifier carried on every event. Default: ${DEFAULT-VALUE}")
    String runId;

    /** How the mapping is executed. */
    enum Engine {
        /** Through Flink, which is how the platform actually runs (spec 5). */
        FLINK,
        /** In-process, no cluster. Faster to start, for iterating on a mapping. */
        DIRECT
    }

    @Option(names = "--engine", defaultValue = "FLINK",
            description = "Execution engine: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}")
    Engine engine;

    @Option(names = "--mode", defaultValue = "BATCH",
            description = "Flink runtime mode: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}")
    ExecutionMode mode;

    @Option(names = "--web-ui",
            description = "Serve Flink's job-graph dashboard while the run executes.")
    boolean webUi;

    @Option(names = "--web-port", defaultValue = "8081",
            description = "Dashboard port. Default: ${DEFAULT-VALUE}")
    int webPort;

    @Option(names = "--pace-millis", defaultValue = "0",
            description = "Delay per source record, so a short run lasts long enough to watch.")
    long paceMillis;

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @Override
    public Integer call() throws Exception {
        ArtifactSet artifacts = ArtifactSet.load(mappingFile, moduleDirectory.resolve("contracts"));
        List<String> crossReference = artifacts.crossReferenceProblems();
        if (!crossReference.isEmpty()) {
            System.err.println("Content is not coherent; run `niem validate` for detail:");
            crossReference.forEach(problem -> System.err.println("  " + problem));
            return 1;
        }

        ConnectorConfig config = ConnectorConfig.of(
                artifacts.mapping().sourceId(),
                "file-drop-cli",
                FileDropConnector.TYPE,
                Map.of(
                        "directory", dropDirectory.toString(),
                        "filePattern", filePattern,
                        "skipHeaderLines", Integer.toString(skipHeaderLines)));

        RecordingObservabilityEmitter recorder = new RecordingObservabilityEmitter();
        // Both sinks: the log for whatever collects container output, the recorder for the
        // summary this command prints. Neither can fail the run.
        ObservabilityEmitter emitter =
                ObservabilityEmitter.composite(recorder, new LoggingObservabilityEmitter());
        QuarantineSink.InMemory quarantine = new QuarantineSink.InMemory();
        InMemoryClusterIndex clusterIndex = new InMemoryClusterIndex();

        FileDropConnector connector = new FileDropConnector();
        connector.configure(config);

        long canonicalCount;
        try (ParquetBronzeStore bronze = new ParquetBronzeStore(bronzeRoot)) {
            var landing = new LandingService(bronze, emitter).land(connector, config, runId);
            System.out.printf("Landed %d record(s) in %d batch(es) for source '%s'.%n",
                    landing.recordsLanded(), landing.receipts().size(), config.sourceId());

            if (landing.receipts().isEmpty()) {
                System.out.println("Nothing to map.");
                return 0;
            }

            // Map only what this run landed. Bronze is append-only and accumulates across runs, so
            // mapping all of it would re-emit every record the platform has ever seen, every time.
            // Re-mapping an existing range is replay's job, not run's.
            BronzeRange landed = BronzeRange.between(
                    landing.receipts().getFirst().batchId(),
                    landing.receipts().getLast().batchId());

            MappingPipeline pipeline = new MappingPipeline(
                    artifacts.mapping(),
                    artifacts.contractsByHop(),
                    Map.of(DeterministicResolutionProvider.PROVIDER_ID, new IndexedResolutionProvider(
                            new DeterministicResolutionProvider(clusterIndex), clusterIndex)),
                    artifacts.canonicalTypes(),
                    quarantine,
                    emitter);

            canonicalCount = engine == Engine.DIRECT
                    ? mapLanded(bronze, config.sourceId(), landed, pipeline)
                    : mapThroughFlink(bronze, config.sourceId(), landed, artifacts, quarantineFile());
        }

        if (engine == Engine.DIRECT) {
            writeQuarantine(quarantine.held());
            summarise(canonicalCount, recorder, quarantine, clusterIndex);
        } else {
            summariseDistributed(canonicalCount);
        }

        // A run that quarantined records is not a failed run -- spec §4.2 is explicit that bad
        // data must not halt the pipeline -- but it is not a clean one either, and a scheduled
        // job should be able to tell the difference.
        //
        // On the Flink engine the driver cannot see the quarantine, so it reports 3: "ran, outcome
        // not determinable here" rather than 0. Claiming a clean run it cannot verify would make
        // the exit code worse than useless to a scheduler.
        if (engine != Engine.DIRECT) {
            return 3;
        }
        return quarantine.size() == 0 ? 0 : 2;
    }

    /** Where the Flink path writes quarantined records, since an in-memory sink cannot travel. */
    private Path quarantineFile() {
        return quarantineOut != null ? quarantineOut.getParent() != null
                ? quarantineOut.getParent() : Path.of(".")
                : bronzeRoot.resolve("quarantine");
    }

    /**
     * Runs the mapping through Flink, which is how the platform actually executes (spec 5).
     *
     * <p>The pipeline is built inside the operator, so its collaborators must be constructible
     * there rather than handed across the job graph. Quarantine therefore goes to a file and events
     * to the log: an in-memory sink in the driver would collect nothing, and a quarantined record
     * that vanished would be exactly the silent drop 4.2 rules out.
     *
     * <p>Identity resolution gets a fresh index per operator, which is correct at parallelism one
     * and would need a shared index above it. Recorded rather than hidden: the engine pins
     * parallelism to one today.
     */
    private long mapThroughFlink(
            ParquetBronzeStore bronze,
            String sourceId,
            BronzeRange range,
            ArtifactSet artifacts,
            Path quarantineDirectory) throws Exception {

        List<RawEnvelope> envelopes;
        try (Stream<RawEnvelope> landed = bronze.read(sourceId, range)) {
            envelopes = landed.toList();
        }

        var definition = artifacts.mapping();
        var contracts = artifacts.contractsByHop();
        var canonicalTypes = artifacts.canonicalTypes();
        String id = runId;
        // Captured as text, not as a Path: Flink serialises the closure, and a platform Path
        // implementation is not Serializable. The Path is rebuilt inside the operator.
        String quarantinePath = quarantineDirectory.toAbsolutePath().toString();

        MappingPipelineFactory factory = () -> new MappingPipeline(
                definition,
                contracts,
                Map.of(DeterministicResolutionProvider.PROVIDER_ID, new IndexedResolutionProvider(
                        new DeterministicResolutionProvider(new InMemoryClusterIndex()),
                        new InMemoryClusterIndex())),
                canonicalTypes,
                new FileQuarantineSink(Path.of(quarantinePath), id),
                new LoggingObservabilityEmitter());

        JobOptions options = webUi
                ? JobOptions.withDashboard(mode, webPort, java.time.Duration.ofMillis(paceMillis))
                : new JobOptions(mode, null, java.time.Duration.ofMillis(paceMillis));

        if (webUi) {
            System.out.printf("Flink dashboard: http://localhost:%d  (available while the run executes)%n",
                    webPort);
        }

        List<Record> canonical = FlinkMappingJob.run(factory, envelopes, options, runId);

        if (canonicalOut != null) {
            try (BufferedWriter out = Files.newBufferedWriter(canonicalOut, StandardCharsets.UTF_8)) {
                for (Record record : canonical) {
                    out.write(json.writeValueAsString(asJson(record)));
                    out.newLine();
                }
            }
        }
        System.out.printf("Quarantine (if any) written under %s.%n", quarantineDirectory);
        return canonical.size();
    }

    private long mapLanded(
            ParquetBronzeStore bronze, String sourceId, BronzeRange range, MappingPipeline pipeline)
            throws IOException {
        if (canonicalOut == null) {
            try (Stream<RawEnvelope> landed = bronze.read(sourceId, range)) {
                return landed.mapToLong(envelope ->
                        pipeline.process(envelope, runId).canonicalRecords().size()).sum();
            }
        }
        long written = 0;
        try (BufferedWriter out = Files.newBufferedWriter(canonicalOut, StandardCharsets.UTF_8);
                Stream<RawEnvelope> landed = bronze.read(sourceId, range)) {
            for (RawEnvelope envelope : (Iterable<RawEnvelope>) landed::iterator) {
                for (Record record : pipeline.process(envelope, runId).canonicalRecords()) {
                    out.write(json.writeValueAsString(asJson(record)));
                    out.newLine();
                    written++;
                }
            }
        }
        return written;
    }

    private Map<String, Object> asJson(Record record) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("type", record.typeName());
        Map<String, Object> values = new LinkedHashMap<>();
        record.values().forEach((name, value) -> values.put(name, value == null ? null : value.toString()));
        document.put("values", values);
        return document;
    }

    private void writeQuarantine(List<QuarantinedRecord> held) throws IOException {
        if (quarantineOut == null || held.isEmpty()) {
            return;
        }
        try (BufferedWriter out = Files.newBufferedWriter(quarantineOut, StandardCharsets.UTF_8)) {
            for (QuarantinedRecord rejected : held) {
                Map<String, Object> document = new LinkedHashMap<>();
                document.put("contract", rejected.contractId().toString());
                document.put("hop", rejected.hopId());
                document.put("direction", rejected.direction().name());
                document.put("failures", rejected.failures().stream().map(Object::toString).toList());
                // Values intact, unlike the event. Quarantine is where the offending data is kept,
                // under the same access controls as bronze. See ADR 0015.
                document.put("record", asJson(rejected.record()));
                out.write(json.writeValueAsString(document));
                out.newLine();
            }
        }
    }

    /**
     * Summary for a run whose pipeline lived inside operators.
     *
     * <p>Reports only what the driver can actually know. The recorder and the cluster index were
     * constructed inside the operator, so the driver saw no events and no clusters -- and printing
     * "No contract violations" on the strength of that would be a lie of exactly the kind this
     * platform exists to prevent. The counts an operator needs are in the event stream and the
     * quarantine file, and this says so.
     */
    private void summariseDistributed(long canonicalCount) {
        System.out.printf("Mapped %d canonical record(s).%n", canonicalCount);
        System.out.println("Violations and cluster counts are not visible from the driver on this "
                + "engine: contract violations are in the event stream above, and quarantined "
                + "records are in the quarantine file named earlier.");
        if (canonicalOut != null) {
            System.out.printf("Canonical records written to %s.%n", canonicalOut);
        }
        System.out.println("Silver was not written: the canonical store is not built yet (ADR 0005).");
    }

    private void summarise(
            long canonicalCount,
            RecordingObservabilityEmitter recorder,
            QuarantineSink.InMemory quarantine,
            InMemoryClusterIndex clusterIndex) {

        System.out.printf("Mapped %d canonical record(s).%n", canonicalCount);
        System.out.printf("Resolved %d person cluster(s).%n", clusterIndex.clusterCount("Person"));

        List<ContractViolation> violations = recorder.eventsOfType(ContractViolation.class);
        if (violations.isEmpty()) {
            System.out.println("No contract violations.");
        } else {
            System.out.printf("%d contract violation(s), %d record(s) quarantined:%n",
                    violations.size(), quarantine.size());
            violations.stream().limit(10).forEach(violation ->
                    System.out.println("  " + violation.summary()));
            if (violations.size() > 10) {
                System.out.printf("  ... and %d more%n", violations.size() - 10);
            }
        }

        if (canonicalOut != null) {
            System.out.printf("Canonical records written to %s.%n", canonicalOut);
        }
        // Said plainly rather than left to be inferred. A run that looked like it had populated
        // silver would be worse than one that refused to run at all.
        System.out.println("Silver was not written: the canonical store is not built yet (ADR 0005).");
    }
}
