package gov.niemplatform.connectors.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.observability.RecordingObservabilityEmitter;
import gov.niemplatform.storage.api.BronzeBatch;
import gov.niemplatform.storage.api.BronzeBatchReceipt;
import gov.niemplatform.storage.api.BronzeRange;
import gov.niemplatform.storage.api.BronzeStore;
import gov.niemplatform.storage.api.RawEnvelope;
import gov.niemplatform.storage.api.SourceOffset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A source position advances only after bronze has the records (spec §4.3, §4.4).
 *
 * <p>Matters for every transport that remembers where it got to -- a Kafka consumer group, a CDC
 * log sequence number, an FTP archive move -- and for none of the ones that do not. The ordering is
 * the difference between a crash that duplicates records and a crash that loses them: bronze can
 * detect a duplicate, because envelope identity is derived from source, offset and content hash,
 * and nothing can detect a record a source believes it already delivered.
 */
class AcknowledgementFollowsTheCommitTest {

    /** Records what happened in the order it happened, which is the only thing under test. */
    private static final List<String> journal = new ArrayList<>();

    private static final class RecordingStore implements BronzeStore {

        private final int failOnBatch;
        private int batches;

        RecordingStore(int failOnBatch) {
            this.failOnBatch = failOnBatch;
        }

        @Override
        public BronzeBatchReceipt append(BronzeBatch batch) {
            batches++;
            if (batches == failOnBatch) {
                journal.add("commit-failed");
                throw new IllegalStateException("bronze is unavailable");
            }
            journal.add("committed " + batch.envelopes().size());
            List<RawEnvelope> envelopes = batch.envelopes();
            return new BronzeBatchReceipt(
                    "batch-%04d".formatted(batches), batch.sourceId(), batch.connectorInstanceId(),
                    envelopes.size(), Instant.parse("2026-03-04T11:20:00Z"),
                    envelopes.getFirst().offset(), envelopes.getLast().offset(),
                    "data/batch-%04d.parquet".formatted(batches),
                    "data/batch-%04d.json".formatted(batches));
        }

        @Override
        public Stream<RawEnvelope> read(String sourceId, BronzeRange range) {
            return Stream.empty();
        }

        @Override
        public List<String> sources() {
            return List.of();
        }

        @Override
        public List<BronzeBatchReceipt> batches(String sourceId) {
            return List.of();
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }

    /** A connector standing in for any transport that holds a position on the source side. */
    private static final class PositionedConnector implements SourceConnector {

        private final int records;

        PositionedConnector(int records) {
            this.records = records;
        }

        @Override
        public ConnectorType type() {
            return ConnectorType.of("positioned");
        }

        @Override
        public InteractionMode interactionMode() {
            return InteractionMode.PUSH;
        }

        @Override
        public RetentionPosture retention() {
            return RetentionPosture.RETAINED;
        }

        @Override
        public void configure(ConnectorConfig config) {
            // Nothing to configure.
        }

        @Override
        public SourceHandle open() {
            Stream<RawEnvelope> envelopes = IntStream.range(0, records)
                    .mapToObj(index -> new RawEnvelope(
                            "positioned-source", "positioned-1",
                            Instant.parse("2026-03-04T11:20:00Z"),
                            Instant.parse("2026-03-04T11:19:00Z"),
                            ("record-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            SourceOffset.of("0:%09d".formatted(index))));
            return new SourceHandle() {

                @Override
                public Stream<RawEnvelope> envelopes() {
                    return envelopes;
                }

                @Override
                public void acknowledge() {
                    journal.add("acknowledged");
                }

                @Override
                public void close() {
                    envelopes.close();
                }
            };
        }

        @Override
        public HealthStatus health() {
            return HealthStatus.healthy(Instant.now());
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }

    private static LandingService landing(BronzeStore store, int batchSize) {
        return new LandingService(
                store, new RecordingObservabilityEmitter(), java.time.Clock.systemUTC(), batchSize);
    }

    private static ConnectorConfig config(SourceConnector connector) {
        return ConnectorConfig.of("positioned-source", "positioned-1", connector.type(), Map.of());
    }

    @Test
    @DisplayName("every acknowledgement follows the commit it acknowledges, batch by batch")
    void acknowledgesAfterEachCommit() {
        journal.clear();
        var connector = new PositionedConnector(5);

        landing(new RecordingStore(0), 2).land(connector, config(connector), "run-1");

        // Two full batches, then the residual. Each commit is immediately followed by its
        // acknowledgement and never preceded by one.
        assertThat(journal).containsExactly(
                "committed 2", "acknowledged",
                "committed 2", "acknowledged",
                "committed 1", "acknowledged");
    }

    @Test
    @DisplayName("a failed commit is not acknowledged, so the source redelivers rather than loses")
    void doesNotAcknowledgeAFailedCommit() {
        journal.clear();
        var connector = new PositionedConnector(5);

        assertThatThrownBy(() ->
                landing(new RecordingStore(2), 2).land(connector, config(connector), "run-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bronze is unavailable");

        // The first batch is committed and acknowledged; the second fails and is not. The source
        // still holds its position at record 2, so those records arrive again on the next open.
        assertThat(journal).containsExactly("committed 2", "acknowledged", "commit-failed");
    }
}
