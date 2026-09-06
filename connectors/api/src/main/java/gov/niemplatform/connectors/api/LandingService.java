package gov.niemplatform.connectors.api;

import gov.niemplatform.observability.ObservabilityEmitter;
import gov.niemplatform.observability.PipelineContext;
import gov.niemplatform.observability.PipelineLag;
import gov.niemplatform.storage.api.BronzeBatch;
import gov.niemplatform.storage.api.BronzeBatchReceipt;
import gov.niemplatform.storage.api.BronzeStore;
import gov.niemplatform.storage.api.RawEnvelope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Drains a connector into bronze.
 *
 * <p>Spec §4.3: "Every connector lands into bronze identically. Transport differences must not
 * leak past the landing boundary." This class is the single place landing happens, so that rule
 * holds by construction rather than by every connector remembering it. A connector that wrote to
 * bronze itself could quietly land a different envelope shape, and nothing would notice until a
 * replay produced different silver.
 *
 * <p>Freshness is evaluated here too, against the source-asserted timestamp rather than ingest
 * time -- a connector happily landing day-old records is on time by ingest time and a day stale
 * by the only measure an investigator cares about (spec §4.7).
 */
public final class LandingService {

    /** Records per bronze batch. Batches are the unit of atomic commit, not a tuning knob. */
    public static final int DEFAULT_BATCH_SIZE = 1_000;

    private final BronzeStore bronze;
    private final ObservabilityEmitter emitter;
    private final Clock clock;
    private final int batchSize;

    public LandingService(BronzeStore bronze, ObservabilityEmitter emitter) {
        this(bronze, emitter, Clock.systemUTC(), DEFAULT_BATCH_SIZE);
    }

    public LandingService(BronzeStore bronze, ObservabilityEmitter emitter, Clock clock, int batchSize) {
        this.bronze = Objects.requireNonNull(bronze, "bronze");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Batch size must be positive, found " + batchSize);
        }
        this.batchSize = batchSize;
    }

    /** What one landing run did. */
    public record LandingResult(
            String sourceId,
            String connectorInstanceId,
            List<BronzeBatchReceipt> receipts,
            long recordsLanded,
            Instant latestSourceTimestamp) {

        public LandingResult {
            receipts = List.copyOf(receipts);
        }

        /** The most recent thing the source asserted, if it asserted anything. */
        public Optional<Instant> latestSourceAssertion() {
            return Optional.ofNullable(latestSourceTimestamp);
        }
    }

    /**
     * Reads everything the handle yields and lands it, committing in batches.
     *
     * <p>Batching bounds memory and bounds the blast radius of a crash: work already committed
     * stays committed, and the batch in flight is simply never made visible.
     */
    public LandingResult land(SourceConnector connector, ConnectorConfig config, String runId) {
        Objects.requireNonNull(connector, "connector");
        Objects.requireNonNull(config, "config");

        List<BronzeBatchReceipt> receipts = new ArrayList<>();
        List<RawEnvelope> pending = new ArrayList<>(batchSize);
        long landed = 0;
        Instant latestAssertion = null;

        try (SourceHandle handle = connector.open();
                Stream<RawEnvelope> envelopes = handle.envelopes()) {

            for (RawEnvelope envelope : (Iterable<RawEnvelope>) envelopes::iterator) {
                pending.add(envelope);
                landed++;
                Instant asserted = envelope.sourceAssertedTimestamp().orElse(null);
                if (asserted != null && (latestAssertion == null || asserted.isAfter(latestAssertion))) {
                    latestAssertion = asserted;
                }
                if (pending.size() >= batchSize) {
                    receipts.add(commit(config, pending));
                    pending.clear();
                }
            }
        }
        if (!pending.isEmpty()) {
            receipts.add(commit(config, pending));
        }

        reportFreshness(config, runId, latestAssertion);

        return new LandingResult(config.sourceId(), config.connectorInstanceId(),
                receipts, landed, latestAssertion);
    }

    private BronzeBatchReceipt commit(ConnectorConfig config, List<RawEnvelope> pending) {
        return bronze.append(new BronzeBatch(
                config.sourceId(), config.connectorInstanceId(), List.copyOf(pending)));
    }

    /**
     * Emits a {@code PipelineLag} when a source has fallen behind its declared SLA.
     *
     * <p>Silent when no SLA is declared: a source with no stated freshness expectation cannot be
     * late, and inventing a default would fill an operator's console with noise they never asked
     * for and would learn to ignore.
     */
    private void reportFreshness(ConnectorConfig config, String runId, Instant latestAssertion) {
        Optional<Duration> sla = config.declaredFreshnessSla();
        if (sla.isEmpty() || latestAssertion == null) {
            return;
        }
        Instant now = clock.instant();
        if (Duration.between(latestAssertion, now).compareTo(sla.get()) > 0) {
            emitter.emit(new PipelineLag(
                    PipelineContext.of(config.sourceId(), runId), latestAssertion, now, sla.get()));
        }
    }
}
