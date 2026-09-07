package gov.niemplatform.cli;

import gov.niemplatform.storage.api.BronzeBatchReceipt;
import gov.niemplatform.storage.api.BronzeRange;
import gov.niemplatform.storage.api.RawEnvelope;
import gov.niemplatform.storage.parquet.ParquetBronzeStore;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Reports what is in bronze.
 *
 * <p>The command an operator reaches for during an incident: what landed, when, from where, and
 * whether it is intact. Reading through {@link ParquetBronzeStore} rather than listing files means
 * the integrity checks run -- a batch whose manifest disagrees with its data file is reported here
 * rather than discovered later in a replay.
 *
 * <p>Payloads are never printed. Bronze holds raw source data, and an operator debugging a landing
 * problem needs sizes, hashes, and offsets, not names and dates of birth on their terminal. Use
 * {@code --payloads} to override that deliberately.
 */
@Command(
        name = "inspect",
        mixinStandardHelpOptions = true,
        description = "Report what has landed in bronze.")
final class InspectCommand implements Callable<Integer> {

    @Option(names = "--bronze", required = true, description = "Bronze storage root.")
    Path bronzeRoot;

    @Option(names = "--source", description = "Limit to one source. Omitted: every source.")
    String sourceId;

    @Option(names = "--batch", description = "Limit to one batch identifier.")
    String batchId;

    @Option(names = "--envelopes", description = "List envelope metadata, not just batch summaries.")
    boolean listEnvelopes;

    @Option(names = "--limit", defaultValue = "20",
            description = "Maximum envelopes to list. Default: ${DEFAULT-VALUE}")
    int limit;

    @Option(names = "--payloads",
            description = "Print payloads. Off by default: bronze holds raw source data.")
    boolean showPayloads;

    @Override
    public Integer call() {
        try (ParquetBronzeStore bronze = new ParquetBronzeStore(bronzeRoot)) {
            List<String> sources = sourceId != null ? List.of(sourceId) : bronze.sources();
            if (sources.isEmpty()) {
                System.out.println("No sources have landed anything under " + bronzeRoot);
                return 0;
            }

            for (String source : sources) {
                reportSource(bronze, source);
            }
            return 0;
        } catch (RuntimeException e) {
            System.err.println("Cannot read bronze at " + bronzeRoot + ": " + e.getMessage());
            return 1;
        }
    }

    private void reportSource(ParquetBronzeStore bronze, String source) {
        List<BronzeBatchReceipt> batches = bronze.batches(source);
        long records = batches.stream().mapToLong(BronzeBatchReceipt::recordCount).sum();

        System.out.printf("%nsource %s%n", source);
        System.out.printf("  %d batch(es), %d record(s)%n", batches.size(), records);

        for (BronzeBatchReceipt batch : batches) {
            if (batchId != null && !batchId.equals(batch.batchId())) {
                continue;
            }
            System.out.printf("  %s  %6d record(s)  landed %s  connector %s%n",
                    batch.batchId(), batch.recordCount(), batch.landedAt(), batch.connectorInstanceId());
            System.out.printf("      offsets %s .. %s%n", batch.firstOffset(), batch.lastOffset());
        }

        if (listEnvelopes) {
            reportEnvelopes(bronze, source);
        }
    }

    private void reportEnvelopes(ParquetBronzeStore bronze, String source) {
        BronzeRange range = batchId == null ? BronzeRange.all() : BronzeRange.batch(batchId);
        System.out.println("  envelopes:");

        // Reading through the store runs its integrity checks: a stored hash that no longer
        // matches its payload raises here rather than silently reaching silver.
        try (Stream<RawEnvelope> envelopes = bronze.read(source, range)) {
            long shown = envelopes.limit(limit).peek(this::printEnvelope).count();
            if (shown == limit) {
                System.out.printf("      (stopped at --limit %d)%n", limit);
            }
        }
    }

    private void printEnvelope(RawEnvelope envelope) {
        System.out.printf("      %s  %-28s %6d bytes  %s%n",
                envelope.envelopeId(),
                envelope.offset(),
                envelope.payloadSize(),
                envelope.contentHash());
        System.out.printf("          ingested %s  source asserted %s%n",
                envelope.ingestTimestamp(),
                envelope.sourceAssertedTimestamp().map(Object::toString).orElse("(none)"));
        if (showPayloads) {
            System.out.printf("          %s%n", envelope.payloadAsText());
        }
    }
}
