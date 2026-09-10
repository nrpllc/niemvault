package gov.niemplatform.connectors.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.connectors.api.ConnectorConfig;
import gov.niemplatform.connectors.api.ConnectorConfigurationException;
import gov.niemplatform.connectors.api.InteractionMode;
import gov.niemplatform.connectors.api.RetentionPosture;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the Kafka connector accepts, refuses, and derives -- none of which needs a broker.
 *
 * <p>Config is validated on load and fails loudly (spec §9, ADR 0010), so the interesting cases are
 * the refusals.
 */
class KafkaConnectorConfigurationTest {

    private static ConnectorConfig config(Map<String, String> overrides) {
        Map<String, String> settings = new LinkedHashMap<>(Map.of(
                "bootstrapServers", "localhost:9092",
                "topic", "cad.incidents",
                "groupId", "niem-ingest",
                "retention", "retained"));
        overrides.forEach((key, value) -> {
            if (value == null) {
                settings.remove(key);
            } else {
                settings.put(key, value);
            }
        });
        return ConnectorConfig.of("riverton-cad", "kafka-1", KafkaConnector.TYPE, settings);
    }

    private static ConsumerRecord<byte[], byte[]> record(
            int partition, long offset, byte[] value, long timestamp, TimestampType timestampType) {
        return new ConsumerRecord<>(
                "cad.incidents", partition, offset, timestamp, timestampType,
                0, value == null ? 0 : value.length, null, value,
                new org.apache.kafka.common.header.internals.RecordHeaders(),
                java.util.Optional.empty());
    }

    @Test
    @DisplayName("a topic is push, whatever schedule the platform reads it on")
    void declaresItsInteractionMode() {
        assertThat(new KafkaConnector().interactionMode()).isEqualTo(InteractionMode.PUSH);
    }

    @Test
    @DisplayName("retention is refused before configure rather than assumed")
    void refusesToGuessRetentionBeforeConfigure() {
        assertThatThrownBy(() -> new KafkaConnector().retention())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before configure()")
                .hasMessageContaining("ADR 0027");
    }

    @Test
    @DisplayName("retention has no default: a topic that does not declare it cannot be configured")
    void requiresAnExplicitRetentionPosture() {
        Map<String, String> withoutRetention = new LinkedHashMap<>();
        withoutRetention.put("retention", null);

        assertThatThrownBy(() -> new KafkaConnector().configure(config(withoutRetention)))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("'retention'")
                .hasMessageContaining("no default")
                .hasMessageContaining("may not lawfully be kept");
    }

    @Test
    @DisplayName("a topic may be declared non-retainable, and landing will then refuse it")
    void acceptsATransientTopic() {
        var connector = new KafkaConnector();
        connector.configure(config(Map.of("retention", "transient")));

        assertThat(connector.retention()).isEqualTo(RetentionPosture.TRANSIENT);
        assertThat(connector.retention().landsInBronze()).isFalse();
    }

    @Test
    @DisplayName("a misspelled setting is named, not ignored")
    void refusesUnrecognisedSettings() {
        assertThatThrownBy(() -> new KafkaConnector().configure(config(Map.of("topics", "cad"))))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("'topics'")
                .hasMessageContaining("unrecognised setting");
    }

    @Test
    @DisplayName("every problem is reported at once, so one pass fixes them all")
    void reportsEveryProblemTogether() {
        Map<String, String> broken = new LinkedHashMap<>();
        broken.put("retention", "maybe");
        broken.put("autoOffsetReset", "middle");
        broken.put("maxRecords", "0");

        assertThatThrownBy(() -> new KafkaConnector().configure(config(broken)))
                .isInstanceOf(ConnectorConfigurationException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.throwable(
                        ConnectorConfigurationException.class))
                .extracting(ConnectorConfigurationException::problems)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(
                        ConnectorConfigurationException.Problem.class))
                .extracting(ConnectorConfigurationException.Problem::settingKey)
                .containsExactlyInAnyOrder("retention", "autoOffsetReset", "maxRecords");
    }

    @Test
    @DisplayName("a settings dump names keys and never values, because one of them is a credential")
    void neverPrintsSettingValues() {
        var connector = new KafkaConnector();
        connector.configure(config(Map.of(
                "saslJaasConfig", "org.apache.kafka.common.security.scram.ScramLoginModule "
                        + "required username=\"ingest\" password=\"hunter2\";")));

        assertThat(connector.toString()).doesNotContain("hunter2").contains("cad.incidents");
    }

    @Test
    @DisplayName("an offset is zero-padded, so lexicographic order is source order")
    void zeroPadsTheOffsetSoItSortsCorrectly() {
        var ninth = KafkaConnector.offsetOf(record(0, 9L, new byte[0], 0L, TimestampType.CREATE_TIME));
        var tenth = KafkaConnector.offsetOf(record(0, 10L, new byte[0], 0L, TimestampType.CREATE_TIME));

        assertThat(ninth.value()).isEqualTo("cad.incidents-000000:0000000000000000009");
        // The point of the padding: unpadded, "9" sorts after "10" and a bronze range covers the
        // wrong records.
        assertThat(ninth).isLessThan(tenth);
    }

    @Test
    @DisplayName("a producer's create time is a source assertion; a broker's append time is not")
    void onlyTreatsCreateTimeAsASourceAssertion() {
        long when = Instant.parse("2026-03-04T11:20:00Z").toEpochMilli();

        assertThat(KafkaConnector.assertedTimestamp(
                record(0, 1L, new byte[0], when, TimestampType.CREATE_TIME)))
                .isEqualTo(Instant.parse("2026-03-04T11:20:00Z"));

        // The broker's own clock stamping arrival is ingest time wearing the wrong label. Treated
        // as a source assertion it would make PipelineLag measure the platform against itself.
        assertThat(KafkaConnector.assertedTimestamp(
                record(0, 1L, new byte[0], when, TimestampType.LOG_APPEND_TIME)))
                .isNull();
        assertThat(KafkaConnector.assertedTimestamp(
                record(0, 1L, new byte[0], -1L, TimestampType.NO_TIMESTAMP_TYPE)))
                .isNull();
    }

    @Test
    @DisplayName("a tombstone lands as an empty payload rather than vanishing")
    void landsTombstonesRatherThanDroppingThem() {
        var envelope = KafkaConnector.envelopeOf(
                record(0, 4L, null, -1L, TimestampType.NO_TIMESTAMP_TYPE),
                "riverton-cad", "kafka-1", Instant.parse("2026-03-04T11:21:00Z"));

        // A dropped record would break the completeness accounting that exists to prove nothing
        // goes missing.
        assertThat(envelope.payloadSize()).isZero();
        assertThat(envelope.offset().value()).endsWith(":0000000000000000004");
    }

    @Test
    @DisplayName("the payload is the value bytes, preserved exactly")
    void preservesTheValueBytes() {
        byte[] row = "2026-000114,BURG,2026/03/04 11:20".getBytes(StandardCharsets.UTF_8);

        var envelope = KafkaConnector.envelopeOf(
                record(2, 7L, row, Instant.parse("2026-03-04T11:20:00Z").toEpochMilli(),
                        TimestampType.CREATE_TIME),
                "riverton-cad", "kafka-1", Instant.parse("2026-03-04T11:21:00Z"));

        assertThat(envelope.payload()).isEqualTo(row);
        assertThat(envelope.sourceAssertedTimestamp())
                .contains(Instant.parse("2026-03-04T11:20:00Z"));
        assertThat(envelope.offset().value()).isEqualTo("cad.incidents-000002:0000000000000000007");
    }
}
