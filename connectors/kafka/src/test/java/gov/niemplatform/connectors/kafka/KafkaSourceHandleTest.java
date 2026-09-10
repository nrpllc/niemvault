package gov.niemplatform.connectors.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.storage.api.RawEnvelope;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a slice ends, and what an acknowledgement is allowed to acknowledge.
 *
 * <p>No broker: {@code MockConsumer} is enough to pin the two properties that actually carry risk,
 * and both are invisible in an end-to-end test that happens to consume everything it polls.
 */
class KafkaSourceHandleTest {

    private static final String TOPIC = "cad.incidents";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);

    /**
     * A clock that moves on every reading.
     *
     * <p>The idle timeout is measured in wall-clock time, so a fixed clock would spin the poll loop
     * forever and a real one would make the test slow and flaky by turns.
     */
    private static final class TickingClock extends Clock {

        private Instant now = Instant.parse("2026-03-04T11:20:00Z");
        private final Duration step;

        TickingClock(Duration step) {
            this.step = step;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            now = now.plus(step);
            return now;
        }
    }

    /**
     * A consumer that has not been given any partitions yet, for as long as the test says.
     *
     * <p>What a real consumer looks like between {@code subscribe()} and the group coordinator
     * handing it partitions: polls return nothing, and the assignment is empty.
     */
    private static final class JoiningConsumer extends SurvivingConsumer {

        private int pollsBeforeAssignment;
        private final List<ConsumerRecord<byte[], byte[]>> pending = new java.util.ArrayList<>();

        JoiningConsumer(int pollsBeforeAssignment, int records) {
            this.pollsBeforeAssignment = pollsBeforeAssignment;
            for (int index = 0; index < records; index++) {
                pending.add(recordAt(index));
            }
        }

        @Override
        public java.util.Set<TopicPartition> assignment() {
            return pollsBeforeAssignment > 0 ? java.util.Set.of() : super.assignment();
        }

        @Override
        public org.apache.kafka.clients.consumer.ConsumerRecords<byte[], byte[]> poll(Duration timeout) {
            if (pollsBeforeAssignment > 0) {
                pollsBeforeAssignment--;
                if (pollsBeforeAssignment == 0) {
                    // The group has formed; the records it was assigned become visible.
                    assign(List.of(PARTITION));
                    updateBeginningOffsets(Map.of(PARTITION, 0L));
                    pending.forEach(this::addRecord);
                }
                return org.apache.kafka.clients.consumer.ConsumerRecords.empty();
            }
            return super.poll(timeout);
        }
    }

    /**
     * A {@code MockConsumer} that stays readable after {@code close()}.
     *
     * <p>The real one refuses every call once closed, which would make "closing commits nothing"
     * untestable -- the assertion has to run after the close it is about.
     */
    private static class SurvivingConsumer extends MockConsumer<byte[], byte[]> {

        private boolean closedByHandle;

        SurvivingConsumer() {
            super(OffsetResetStrategy.EARLIEST);
        }

        @Override
        public void close() {
            closedByHandle = true;
        }

        boolean wasClosed() {
            return closedByHandle;
        }
    }

    private static ConsumerRecord<byte[], byte[]> recordAt(int index) {
        return new ConsumerRecord<>(
                TOPIC, 0, index, Instant.parse("2026-03-04T11:20:00Z").toEpochMilli(),
                TimestampType.CREATE_TIME, 0, 4, null,
                ("row-" + index).getBytes(StandardCharsets.UTF_8),
                new org.apache.kafka.common.header.internals.RecordHeaders(),
                java.util.Optional.empty());
    }

    private static SurvivingConsumer consumerHolding(int records) {
        var consumer = new SurvivingConsumer();
        consumer.assign(List.of(PARTITION));
        consumer.updateBeginningOffsets(Map.of(PARTITION, 0L));
        for (int index = 0; index < records; index++) {
            consumer.addRecord(recordAt(index));
        }
        return consumer;
    }

    private static KafkaSourceHandle handle(
            SurvivingConsumer consumer, int maxRecords, Duration idle, Clock clock) {
        return new KafkaSourceHandle(
                consumer, "riverton-cad", "kafka-1", TOPIC, maxRecords, idle,
                Duration.ofMillis(1), clock);
    }

    @Test
    @DisplayName("an acknowledgement covers what was handed over, not what was polled")
    void acknowledgesOnlyWhatWasYielded() {
        var consumer = consumerHolding(5);
        // One poll returns all five. The caller takes two, which is the shape LandingService
        // produces whenever a bronze batch is smaller than a poll.
        var handle = handle(consumer, 100, Duration.ofSeconds(30), new TickingClock(Duration.ofMillis(1)));

        try (Stream<RawEnvelope> envelopes = handle.envelopes()) {
            List<RawEnvelope> taken = envelopes.limit(2).toList();
            assertThat(taken).hasSize(2);
        }
        handle.acknowledge();

        // Two, not five. Committing the consumer's own position would acknowledge three records
        // the caller never saw and bronze never received -- and a broker that considers them
        // delivered will not send them again.
        assertThat(consumer.committed(java.util.Set.of(PARTITION)).get(PARTITION).offset())
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("nothing handed over, nothing acknowledged")
    void acknowledgesNothingWhenNothingWasYielded() {
        var consumer = consumerHolding(3);
        var handle = handle(consumer, 100, Duration.ofSeconds(30), new TickingClock(Duration.ofMillis(1)));

        handle.acknowledge();

        assertThat(consumer.committed(java.util.Set.of(PARTITION))).isEmpty();
    }

    @Test
    @DisplayName("a quiet topic ends the slice instead of blocking forever")
    void endsTheSliceWhenTheTopicGoesQuiet() {
        var consumer = consumerHolding(2);
        // Each clock reading advances 100ms against a 50ms idle window, so the first empty poll
        // after the records ends the slice.
        var handle = handle(consumer, 100, Duration.ofMillis(50), new TickingClock(Duration.ofMillis(100)));

        try (Stream<RawEnvelope> envelopes = handle.envelopes()) {
            assertThat(envelopes.toList()).hasSize(2);
        }

        // A caught-up feed is the normal case, not an error. The next scheduled open resumes from
        // the committed position.
        assertThat(handle.emitted()).isEqualTo(2);
    }

    @Test
    @DisplayName("a large backlog is cut into slices rather than read in one unbounded gulp")
    void stopsAtTheRecordCeiling() {
        var consumer = consumerHolding(10);
        var handle = handle(consumer, 4, Duration.ofSeconds(30), new TickingClock(Duration.ofMillis(1)));

        try (Stream<RawEnvelope> envelopes = handle.envelopes()) {
            List<RawEnvelope> slice = envelopes.toList();
            assertThat(slice).hasSize(4);
            assertThat(slice.getLast().offset().value()).endsWith(":0000000000000000003");
        }
        handle.acknowledge();

        assertThat(consumer.committed(java.util.Set.of(PARTITION)).get(PARTITION).offset())
                .isEqualTo(4L);
    }

    @Test
    @DisplayName("the idle window does not start until the group has assigned partitions")
    void doesNotMistakeJoiningForAQuietTopic() {
        // Ten empty polls while the group forms, against an idle window of one tick. Before this
        // was separated out, the slice ended during the rebalance and the run reported landing zero
        // records from a topic holding three -- with exit code 0.
        var consumer = new JoiningConsumer(10, 3);
        var handle = handle(consumer, 100, Duration.ofMillis(50), new TickingClock(Duration.ofMillis(100)));

        try (Stream<RawEnvelope> envelopes = handle.envelopes()) {
            assertThat(envelopes.toList()).hasSize(3);
        }
    }

    @Test
    @DisplayName("a subscription that never gets partitions is an error, not an empty topic")
    void refusesToReportABrokenSubscriptionAsNothingToLand() {
        // Never assigned. Landing nothing here would look exactly like a caught-up feed, and a
        // scheduled ingest would report success forever while reading nothing at all.
        var consumer = new JoiningConsumer(Integer.MAX_VALUE, 3);
        var handle = handle(consumer, 100, Duration.ofMillis(50), new TickingClock(Duration.ofSeconds(5)));

        try (Stream<RawEnvelope> envelopes = handle.envelopes()) {
            org.assertj.core.api.Assertions.assertThatThrownBy(envelopes::toList)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("did not assign any partition")
                    .hasMessageContaining(TOPIC);
        }
    }

    @Test
    @DisplayName("closing commits nothing, so an unlanded batch arrives again")
    void closingDoesNotCommit() {
        var consumer = consumerHolding(3);
        var handle = handle(consumer, 100, Duration.ofMillis(50), new TickingClock(Duration.ofMillis(100)));

        try (Stream<RawEnvelope> envelopes = handle.envelopes()) {
            envelopes.toList();
        }
        handle.close();

        // Closing is not a landing event. Anything landed was already acknowledged; anything else
        // is deliberately left for redelivery.
        assertThat(consumer.wasClosed()).isTrue();
        assertThat(consumer.committed(java.util.Set.of(PARTITION))).isEmpty();
    }
}
