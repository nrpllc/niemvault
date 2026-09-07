package gov.niemplatform.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.connectors.api.ConnectorConfig;
import gov.niemplatform.connectors.api.LandingService;
import gov.niemplatform.connectors.file.FileDropConnector;
import gov.niemplatform.contracts.QuarantineSink;
import gov.niemplatform.contracts.QuarantinedRecord;
import gov.niemplatform.identity.api.InMemoryClusterIndex;
import gov.niemplatform.identity.api.IndexedResolutionProvider;
import gov.niemplatform.identity.internal.DeterministicResolutionProvider;
import gov.niemplatform.observability.ContractViolation;
import gov.niemplatform.observability.LoggingObservabilityEmitter;
import gov.niemplatform.observability.ObservabilityEmitter;
import gov.niemplatform.observability.RecordingObservabilityEmitter;
import gov.niemplatform.runtime.engine.MappingPipeline;
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

            MappingPipeline pipeline = new MappingPipeline(
                    artifacts.mapping(),
                    artifacts.contractsByHop(),
                    Map.of(DeterministicResolutionProvider.PROVIDER_ID, new IndexedResolutionProvider(
                            new DeterministicResolutionProvider(clusterIndex), clusterIndex)),
                    artifacts.canonicalTypes(),
                    quarantine,
                    emitter);

            canonicalCount = mapLanded(bronze, config.sourceId(), pipeline);
        }

        writeQuarantine(quarantine.held());
        summarise(canonicalCount, recorder, quarantine, clusterIndex);

        // A run that quarantined records is not a failed run -- spec §4.2 is explicit that bad
        // data must not halt the pipeline -- but it is not a clean one either, and a scheduled
        // job should be able to tell the difference.
        return quarantine.size() == 0 ? 0 : 2;
    }

    private long mapLanded(ParquetBronzeStore bronze, String sourceId, MappingPipeline pipeline)
            throws IOException {
        if (canonicalOut == null) {
            try (Stream<RawEnvelope> landed = bronze.read(sourceId, BronzeRange.all())) {
                return landed.mapToLong(envelope ->
                        pipeline.process(envelope, runId).canonicalRecords().size()).sum();
            }
        }
        long written = 0;
        try (BufferedWriter out = Files.newBufferedWriter(canonicalOut, StandardCharsets.UTF_8);
                Stream<RawEnvelope> landed = bronze.read(sourceId, BronzeRange.all())) {
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
