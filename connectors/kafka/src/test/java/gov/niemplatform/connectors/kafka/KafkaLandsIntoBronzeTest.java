package gov.niemplatform.connectors.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.connectors.api.ConnectorConfig;
import gov.niemplatform.connectors.api.LandingService;
import gov.niemplatform.observability.RecordingObservabilityEmitter;
import gov.niemplatform.storage.api.BronzeBatch;
import gov.niemplatform.storage.api.BronzeBatchReceipt;
import gov.niemplatform.storage.api.BronzeRange;
import gov.niemplatform.storage.api.BronzeStore;
import gov.niemplatform.storage.api.RawEnvelope;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * A topic lands in bronze like any other source, and resumes where it stopped.
 *
 * <p>Tagged {@code docker} and excluded from the everyday loop; run with {@code -Pdocker}. The
 * properties under test -- an offset that survives a slice, and a failed commit that redelivers
 * rather than loses -- are exactly the ones a fake broker cannot honestly stand in for.
 */
@Tag("docker")
@Testcontainers
class KafkaLandsIntoBronzeTest {

    @Container
    private final KafkaContainer broker = new KafkaContainer("apache/kafka:3.8.0");

    private static final String SOURCE_ID = "riverton-cad";
    private static final List<String> ROWS = List.of(
            "2026-000114,BURG,2026/03/04 11:20,\"418 W 9TH ST\",3A,VICT,\"DOE, JANE M\",03/14/1988,F,K447-1902",
            "2026-000114,BURG,2026/03/04 11:20,\"418 W 9TH ST\",3A,WITN,\"NAKAMURA, HIRO\",07/22/1991,M,UNK",
            "2026-000115,ASSLT,2026/03/04 12:05,\"22 ELM AVE\",2B,SUSP,\"RIVERA, LUIS\",11/02/1975,M,UNK",
            "2026-000115,ASSLT,2026/03/04 12:05,\"22 ELM AVE\",2B,VICT,\"O'BRIEN, SEAN P\",01/05/1970,M,D-9930118");

    /** Collects what landed, and can be told to fail, which is the interesting half. */
    private static final class CollectingStore implements BronzeStore {

        private final List<RawEnvelope> landed = new ArrayList<>();
        private final Map<String, BronzeBatchReceipt> receipts = new LinkedHashMap<>();
        private int failOnBatch;
        private int batches;

        void failOn(int batch) {
            this.failOnBatch = batch;
        }

        @Override
        public BronzeBatchReceipt append(BronzeBatch batch) {
            batches++;
            if (batches == failOnBatch) {
                throw new IllegalStateException("bronze is unavailable");
            }
            landed.addAll(batch.envelopes());
            var receipt = new BronzeBatchReceipt(
                    "batch-%04d".formatted(batches), batch.sourceId(), batch.connectorInstanceId(),
                    batch.envelopes().size(), Instant.now(),
                    batch.envelopes().getFirst().offset(), batch.envelopes().getLast().offset(),
                    "data/batch.parquet", "data/batch.json");
            receipts.put(receipt.batchId(), receipt);
            return receipt;
        }

        @Override
        public Stream<RawEnvelope> read(String sourceId, BronzeRange range) {
            return landed.stream();
        }

        @Override
        public List<String> sources() {
            return List.of(SOURCE_ID);
        }

        @Override
        public List<BronzeBatchReceipt> batches(String sourceId) {
            return List.copyOf(receipts.values());
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }

    private void publish(String topic, List<String> rows) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        try (Producer<byte[], byte[]> producer = new KafkaProducer<>(properties)) {
            for (String row : rows) {
                producer.send(new ProducerRecord<>(
                        topic, null, Instant.parse("2026-03-04T18:20:00Z").toEpochMilli(),
                        row.split(",")[0].getBytes(StandardCharsets.UTF_8),
                        row.getBytes(StandardCharsets.UTF_8)));
            }
            producer.flush();
        }
    }

    private KafkaConnector connectorFor(String topic, String groupId, Map<String, String> extra) {
        Map<String, String> settings = new LinkedHashMap<>(Map.of(
                "bootstrapServers", broker.getBootstrapServers(),
                "topic", topic,
                "groupId", groupId,
                "retention", "retained",
                // Short, so a caught-up topic ends its slice promptly rather than holding the test.
                "idleMillis", "2000",
                "pollMillis", "250"));
        settings.putAll(extra);
        var connector = new KafkaConnector();
        connector.configure(ConnectorConfig.of(SOURCE_ID, "kafka-1", KafkaConnector.TYPE, settings));
        return connector;
    }

    private static ConnectorConfig configFor(KafkaConnector connector, String topic, String groupId) {
        return ConnectorConfig.of(SOURCE_ID, "kafka-1", connector.type(), Map.of(
                "bootstrapServers", "unused-by-landing", "topic", topic, "groupId", groupId,
                "retention", "retained"));
    }

    @Test
    @Timeout(120)
    @DisplayName("a topic lands byte-preserved, and a second slice resumes where the first stopped")
    void landsATopicAndResumes() {
        String topic = "cad.incidents";
        publish(topic, ROWS);

        var store = new CollectingStore();
        var landing = new LandingService(store, new RecordingObservabilityEmitter());
        var connector = connectorFor(topic, "niem-ingest", Map.of());
        var config = configFor(connector, topic, "niem-ingest");

        var first = landing.land(connector, config, "run-1");

        assertThat(first.recordsLanded()).isEqualTo(ROWS.size());
        assertThat(store.landed).extracting(RawEnvelope::payloadAsText)
                .containsExactlyElementsOf(ROWS);
        // The producer's create time is what the source asserted, and it is what freshness is
        // measured against.
        assertThat(first.latestSourceAssertion()).contains(Instant.parse("2026-03-04T18:20:00Z"));
        assertThat(store.landed.getFirst().offset().value())
                .isEqualTo("cad.incidents-000000:0000000000000000000");

        // The second slice finds nothing, because the first acknowledged what it landed. This is
        // what makes a scheduled ingest a continuous feed rather than a repeated re-read.
        var second = landing.land(connector, config, "run-2");
        assertThat(second.recordsLanded()).isZero();
        assertThat(store.landed).hasSize(ROWS.size());
    }

    @Test
    @Timeout(120)
    @DisplayName("a failed commit redelivers rather than loses, and the redelivery is detectable")
    void redeliversWhatBronzeNeverReceived() {
        String topic = "cad.incidents.failing";
        publish(topic, ROWS);

        var store = new CollectingStore();
        // A batch size of two against four records: the first batch lands and is acknowledged, the
        // second fails on its way to bronze.
        var landing = new LandingService(
                store, new RecordingObservabilityEmitter(), java.time.Clock.systemUTC(), 2);
        var connector = connectorFor(topic, "niem-ingest-failing", Map.of());
        var config = configFor(connector, topic, "niem-ingest-failing");

        store.failOn(2);
        assertThatThrownBy(() -> landing.land(connector, config, "run-1"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.landed).hasSize(2);

        List<String> firstIdentities = store.landed.stream().map(RawEnvelope::envelopeId).toList();

        // Nothing was acknowledged past the first batch, so the rest arrives again.
        store.failOn(0);
        var recovery = landing.land(connector, config, "run-2");

        assertThat(recovery.recordsLanded()).isEqualTo(2);
        assertThat(store.landed).extracting(RawEnvelope::payloadAsText)
                .containsExactlyElementsOf(ROWS);
        // And had the failure fallen the other way -- a redelivery of something already landed --
        // it would be exactly detectable, because identity is derived from source, offset and
        // content hash rather than generated.
        assertThat(store.landed.stream().map(RawEnvelope::envelopeId).toList())
                .startsWith(firstIdentities.toArray(String[]::new))
                .doesNotHaveDuplicates();
    }

    @Test
    @Timeout(120)
    @DisplayName("a reachable topic is healthy; a missing one is not silently fine")
    void reportsHealthAgainstARealBroker() {
        String topic = "cad.incidents.health";
        publish(topic, ROWS);

        assertThat(connectorFor(topic, "niem-health", Map.of()).health().isHealthy()).isTrue();

        var missing = connectorFor("no.such.topic", "niem-health", Map.of()).health();
        assertThat(missing.isHealthy()).isFalse();
        assertThat(missing.detail()).contains("no.such.topic");
    }
}
