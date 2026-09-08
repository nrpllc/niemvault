package gov.niemplatform.connectors.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.storage.api.BronzeBatch;
import gov.niemplatform.storage.api.BronzeBatchReceipt;
import gov.niemplatform.storage.api.BronzeRange;
import gov.niemplatform.storage.api.BronzeStore;
import gov.niemplatform.storage.api.RawEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A source whose records may not be kept is not landed (ADR 0027).
 *
 * <p>Responses from criminal history systems are frequently not retainable: the rules permit use for
 * the purpose at hand and forbid keeping a copy. Bronze is append-only, so a transient record written
 * by mistake cannot be taken back out — which is why this is refused at the point of landing rather
 * than filtered afterwards.
 */
class TransientSourceIsNotLandedTest {

    /** A connector for something the agency is not permitted to retain. */
    private static final class CriminalHistoryQuery implements SourceConnector {

        @Override
        public ConnectorType type() {
            return ConnectorType.of("state-criminal-history");
        }

        @Override
        public InteractionMode interactionMode() {
            return InteractionMode.QUERY;
        }

        @Override
        public RetentionPosture retention() {
            return RetentionPosture.TRANSIENT;
        }

        @Override
        public void configure(ConnectorConfig config) {
            // Nothing to configure for this test.
        }

        @Override
        public SourceHandle open() {
            throw new AssertionError(
                    "the source was opened; landing must refuse before it reads anything");
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

    /** A store that fails the test if anything reaches it. */
    private static final class RefusingStore implements BronzeStore {

        @Override
        public BronzeBatchReceipt append(BronzeBatch batch) {
            throw new AssertionError("a transient source reached bronze, which is append-only");
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

    @Test
    @DisplayName("landing a non-retainable source is refused before it is even opened")
    void refusesToLandATransientSource() {
        var connector = new CriminalHistoryQuery();
        var config = ConnectorConfig.of("state-chri", "chri-1", connector.type(), Map.of());
        var landing = new LandingService(new RefusingStore(), new gov.niemplatform.observability.RecordingObservabilityEmitter());

        assertThatThrownBy(() -> landing.land(connector, config, "run-1"))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("may not be retained")
                .hasMessageContaining("state-chri");
    }
}
