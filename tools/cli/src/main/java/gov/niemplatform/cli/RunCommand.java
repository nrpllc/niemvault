package gov.niemplatform.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.connectors.api.ConnectorConfig;
import gov.niemplatform.connectors.api.ConnectorRegistry;
import gov.niemplatform.connectors.api.LandingService;
import gov.niemplatform.connectors.api.SourceConnector;
import gov.niemplatform.connectors.api.SourceDefinition;
import gov.niemplatform.connectors.api.SourceDefinitionException;
import gov.niemplatform.connectors.file.FileDropConnector;
import gov.niemplatform.contracts.FileQuarantineSink;
import gov.niemplatform.contracts.QuarantineSink;
import gov.niemplatform.contracts.QuarantinedRecord;
import gov.niemplatform.identity.api.InMemoryClusterIndex;
import gov.niemplatform.identity.api.IndexedResolutionProvider;
import gov.niemplatform.identity.internal.DeterministicResolutionProvider;
import gov.niemplatform.observability.CompletenessBreach;
import gov.niemplatform.observability.ContractViolation;
import gov.niemplatform.observability.LoggingObservabilityEmitter;
import gov.niemplatform.observability.ObservabilityEmitter;
import gov.niemplatform.observability.PipelineContext;
import gov.niemplatform.observability.RecordAccount;
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
import java.util.Optional;
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
        description = "Land a source into bronze and map it to canonical.")
final class RunCommand implements Callable<Integer> {

    @Option(names = {"-m", "--module"}, required = true,
            description = "Domain module directory containing mappings/ and contracts/.")
    Path moduleDirectory;

    @Option(names = "--tenant", required = true,
            description = "The agency this data belongs to, e.g. co.riverton.pd. Required even on a "
                    + "deployment that hosts one: cluster identities are tenant-scoped, so an "
                    + "implied tenant is how a shared deployment becomes commingled (ADR 0025).")
    String tenant;


    @Option(names = "--mapping", required = true, description = "Mapping artifact to run.")
    Path mappingFile;

    /**
     * How the source is described.
     *
     * <p>Two ways in, because a file drop is worth a shorthand and nothing else is. {@code --drop}
     * builds a file-drop configuration from flags; {@code --source} reads any transport's
     * configuration from an artifact on disk. A Kafka source needs a broker, a topic, a group and a
     * retention posture, and a CDC source will need a log position -- none of which belongs on a
     * command line shared with all the others.
     */
    @picocli.CommandLine.ArgGroup(multiplicity = "1")
    SourceOptions source;

    static final class SourceOptions {

        @Option(names = "--drop",
                description = "Directory the source drops files into. Shorthand for a file-drop source.")
        Path dropDirectory;

        @Option(names = "--source",
                description = "Source definition artifact (YAML) naming the transport and its settings.")
        Path definitionFile;
    }

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

    /** Where canonical records are stored. Shared with `replay` so the two cannot disagree. */
    @picocli.CommandLine.Mixin
    SilverOptions silver = new SilverOptions();

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

        // Checked before anything lands. Finding out about a missing credential after a feed has
        // been ingested but before it could be stored leaves an operator replaying to catch up.
        var silverProblem = silver.problem();
        if (silverProblem.isPresent()) {
            System.err.println(silverProblem.get() + ".");
            return 1;
        }

        SourceDefinition definition;
        try {
            definition = sourceDefinition(artifacts.mapping().sourceId());
        } catch (SourceDefinitionException e) {
            System.err.println(e.getMessage());
            return 1;
        }
        ConnectorConfig config = definition.toConnectorConfig();

        RecordingObservabilityEmitter recorder = new RecordingObservabilityEmitter();
        // Both sinks: the log for whatever collects container output, the recorder for the
        // summary this command prints. Neither can fail the run.
        ObservabilityEmitter emitter =
                ObservabilityEmitter.composite(recorder, new LoggingObservabilityEmitter());
        QuarantineSink.InMemory quarantine = new QuarantineSink.InMemory();
        InMemoryClusterIndex clusterIndex = new InMemoryClusterIndex(gov.niemplatform.canonical.meta.TenantId.of(tenant));

        SourceConnector connector;
        try {
            // Resolved through the registry rather than constructed here. The CLI knowing how to
            // build one connector is what made adding a second one a change to this command
            // (spec §4.3: connectors are discovered, not listed).
            connector = definition.connectorFrom(ConnectorRegistry.discover());
        } catch (SourceDefinitionException | gov.niemplatform.connectors.api.ConnectorConfigurationException e) {
            System.err.println(e.getMessage());
            return 1;
        }
        System.out.printf("Source '%s' over %s (%s, %s).%n",
                definition.sourceId(), definition.type(),
                connector.interactionMode(), connector.retention());

        // Asked before anything lands, and asked of the connector rather than inferred. Configuring
        // says the settings are well-formed; only this says the source is actually reachable. A run
        // that discovers an absent drop directory or an unreachable broker while draining has
        // already committed part of a batch, and an operator then has to work out how much.
        var health = connector.health();
        if (!health.isHealthy()) {
            System.err.printf("Source '%s' is %s: %s%n",
                    definition.sourceId(), health.state(), health.detail());
            return 1;
        }

        long canonicalCount;
        RecordAccount account = null;
        Map<String, Long> silverWritten = Map.of();
        var silverStore = silver.open();
        try (ParquetBronzeStore bronze = new ParquetBronzeStore(bronzeRoot, gov.niemplatform.canonical.meta.TenantId.of(tenant))) {
            SilverWriter silverWriter = silverStore
                    .map(store -> new SilverWriter(store, artifacts.canonicalTypes()))
                    .orElse(null);
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

            account = pipeline.account();
            canonicalCount = engine == Engine.DIRECT
                    ? mapLanded(bronze, config.sourceId(), landed, pipeline, silverWriter)
                    : mapThroughFlink(bronze, config.sourceId(), landed, artifacts, quarantineFile(),
                            silverWriter);

            if (silverWriter != null) {
                silverWriter.flush();
                silverWritten = silverWriter.written();
            }
        } finally {
            silverStore.ifPresent(store -> store.close());
        }

        boolean balanced = true;
        if (engine == Engine.DIRECT) {
            writeQuarantine(quarantine.held());
            summarise(canonicalCount, recorder, quarantine, clusterIndex);
            balanced = reportCompleteness(account, emitter, config.sourceId());
        } else {
            summariseDistributed(canonicalCount);
        }
        reportSilver(silverWritten);

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
        // A breach outranks a clean run and a quarantining one alike. Quarantined records are
        // accounted for -- the platform knows where they went and can show them to you. An
        // unbalanced run means it cannot say what happened to data an agency handed it, and that
        // must never exit zero.
        if (!balanced) {
            return 4;
        }
        return quarantine.size() == 0 ? 0 : 2;
    }

    /**
     * The source this run reads, however it was described.
     *
     * <p>The file-drop shorthand builds a definition rather than a connector, so both routes reach
     * the same place. One code path from here down means a Kafka source and a file drop are landed
     * by identical code -- which is spec §4.3's rule that transport differences must not leak past
     * the landing boundary, applied to the operator surface as well as to the runtime.
     *
     * @param sourceId the mapping's source, used when the shorthand supplies no definition
     */
    private SourceDefinition sourceDefinition(String sourceId) {
        if (source.definitionFile != null) {
            SourceDefinition definition = SourceDefinition.load(source.definitionFile);
            if (!definition.sourceId().equals(sourceId)) {
                // A mapping is written against a named source. Landing a different one would map
                // cleanly and produce canonical records about the wrong feed, which no contract
                // catches because the records are perfectly well-formed.
                throw new SourceDefinitionException(source.definitionFile, List.of(
                        "declares source '" + definition.sourceId() + "', but the mapping "
                                + mappingFile.getFileName() + " is written against source '"
                                + sourceId + "'"));
            }
            return definition;
        }
        return new SourceDefinition(
                sourceId,
                "file-drop-cli",
                FileDropConnector.TYPE,
                Map.of(
                        "directory", source.dropDirectory.toString(),
                        "filePattern", filePattern,
                        "skipHeaderLines", Integer.toString(skipHeaderLines)),
                null);
    }

    /**
     * Reports whether the run's books balance, and emits a breach when they do not.
     *
     * <p>Checked after everything has been processed rather than during. §4.2 requires bad data not
     * to halt the pipeline, and that applies to this too: stopping mid-run on a residual would
     * abandon records that were about to be mapped perfectly well, and would make the residual
     * larger rather than smaller.
     *
     * @return whether the account balanced
     */
    private boolean reportCompleteness(
            RecordAccount account, ObservabilityEmitter emitter, String sourceId) {
        if (account == null) {
            return true;
        }
        System.out.printf("Completeness: %s%n", account.summary());

        PipelineContext context = PipelineContext.of(sourceId, runId);
        Optional<CompletenessBreach> breach = account.breach(context);
        if (breach.isEmpty()) {
            return true;
        }

        emitter.emit(breach.get());
        System.err.println("COMPLETENESS BREACH: " + breach.get().summary());
        System.err.println("Records landed that the platform cannot account for. Bronze holds the "
                + "source data and can be replayed; do not treat this run's silver as complete.");
        return false;
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
            Path quarantineDirectory,
            SilverWriter silverWriter) throws Exception {

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
        // Likewise a local. Reading `tenant` inside the lambda would capture `this`, and dragging
        // the whole picocli command into a Flink closure fails at serialisation -- after the data
        // has landed, which is the worst place to find out.
        gov.niemplatform.canonical.meta.TenantId agency =
                gov.niemplatform.canonical.meta.TenantId.of(tenant);

        MappingPipelineFactory factory = () -> new MappingPipeline(
                definition,
                contracts,
                Map.of(DeterministicResolutionProvider.PROVIDER_ID, new IndexedResolutionProvider(
                        new DeterministicResolutionProvider(new InMemoryClusterIndex(agency)),
                        new InMemoryClusterIndex(agency))),
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
        if (silverWriter != null) {
            silverWriter.acceptAll(canonical);
        }

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
            ParquetBronzeStore bronze, String sourceId, BronzeRange range, MappingPipeline pipeline,
            SilverWriter silverWriter) throws IOException {
        if (canonicalOut == null) {
            try (Stream<RawEnvelope> landed = bronze.read(sourceId, range)) {
                return landed.mapToLong(envelope -> {
                    List<Record> produced = pipeline.process(envelope, runId).canonicalRecords();
                    if (silverWriter != null) {
                        silverWriter.acceptAll(produced);
                    }
                    return produced.size();
                }).sum();
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
    }

    /**
     * Says what reached the canonical store, or that nothing did and why.
     *
     * <p>Never silently nothing. A scheduled ingest that lands and maps but stores nothing looks
     * healthy in every log line except the one that matters.
     */
    private void reportSilver(Map<String, Long> written) {
        if (!silver.requested()) {
            System.out.println(
                    "Silver was not written: no --silver-catalog-uri given, so records were mapped "
                            + "and discarded. Bronze holds the source data and can be replayed.");
            return;
        }
        System.out.printf("Silver: %s%n", silver.describe());
        written.forEach((type, count) ->
                System.out.printf("  %-28s %d record(s) appended%n", type, count));
        if (written.isEmpty()) {
            System.out.println("  nothing appended");
        }
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
    }
}
