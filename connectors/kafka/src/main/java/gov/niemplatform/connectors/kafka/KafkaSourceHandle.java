package gov.niemplatform.connectors.kafka;

import gov.niemplatform.connectors.api.SourceHandle;
import gov.niemplatform.storage.api.RawEnvelope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

/**
 * One bounded slice of a topic, drained into envelopes.
 *
 * <p>The slice ends on whichever comes first: {@code maxRecords} records, or {@code idle} elapsed
 * with the topic quiet. Both are needed. Without a record ceiling, a first read of a large backlog
 * never returns; without an idle timeout, a caught-up feed blocks forever on a topic that is simply
 * not busy. Ending on either is what turns an endless topic into something
 * {@code LandingService} can drain exactly as it drains a directory.
 */
final class KafkaSourceHandle implements SourceHandle {

    /**
     * How long a slice waits to be given partitions before calling the subscription broken.
     *
     * <p>Generous against a group rebalance, which is seconds, and short against a wedged ingest.
     */
    private static final Duration ASSIGNMENT_TIMEOUT = Duration.ofSeconds(60);

    private final Consumer<byte[], byte[]> consumer;
    private final String sourceId;
    private final String connectorInstanceId;
    private final String topic;
    private final int maxRecords;
    private final Duration idle;
    private final Duration poll;
    private final Clock clock;

    /** Polled but not yet handed to the caller. Their offsets are not acknowledgeable. */
    private final Deque<ConsumerRecord<byte[], byte[]>> buffered = new ArrayDeque<>();

    /**
     * The furthest offset per partition that has actually been <em>yielded</em>, plus one.
     *
     * <p>Not the consumer's own position, and the distinction is the whole safety property. A poll
     * returns up to 500 records at once and the consumer's position jumps to the end of them
     * immediately; the caller may have taken only the first hundred and committed only those to
     * bronze. A bare {@code commitSync()} would acknowledge all 500 -- and the 400 the caller never
     * saw would never be redelivered, having been lost between a broker that considers them
     * delivered and a bronze that never received them.
     */
    private final Map<TopicPartition, OffsetAndMetadata> yielded = new LinkedHashMap<>();

    private int emitted;
    private Instant lastRecordAt;
    private final Instant openedAt;
    /** Whether the group has given this consumer any partitions yet. */
    private boolean assigned;
    private boolean closed;

    KafkaSourceHandle(
            Consumer<byte[], byte[]> consumer,
            String sourceId,
            String connectorInstanceId,
            String topic,
            int maxRecords,
            Duration idle,
            Duration poll,
            Clock clock) {
        this.consumer = consumer;
        this.sourceId = sourceId;
        this.connectorInstanceId = connectorInstanceId;
        this.topic = topic;
        this.maxRecords = maxRecords;
        this.idle = idle;
        this.poll = poll;
        this.clock = clock;
        this.openedAt = clock.instant();
        this.lastRecordAt = this.openedAt;
    }

    @Override
    public Stream<RawEnvelope> envelopes() {
        Iterator<RawEnvelope> iterator = new Iterator<>() {

            @Override
            public boolean hasNext() {
                return fill();
            }

            @Override
            public RawEnvelope next() {
                if (!fill()) {
                    throw new NoSuchElementException("the slice is drained");
                }
                return emit(buffered.removeFirst());
            }
        };
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL),
                false);
    }

    /**
     * Ensures a record is buffered, polling until one arrives or the slice ends.
     *
     * @return whether the slice has another record
     */
    private boolean fill() {
        // The ceiling is checked before the buffer, not after. One poll returns up to 500 records
        // at once, so a slice bounded only when the buffer runs dry would overshoot maxRecords by
        // most of a poll and hand the caller a batch it never asked for.
        if (emitted >= maxRecords) {
            return false;
        }
        if (!buffered.isEmpty()) {
            return true;
        }
        while (true) {
            ConsumerRecords<byte[], byte[]> polled = consumer.poll(poll);
            if (!polled.isEmpty()) {
                for (ConsumerRecord<byte[], byte[]> record : polled) {
                    buffered.addLast(record);
                }
                assigned = true;
                lastRecordAt = clock.instant();
                return true;
            }
            if (!assigned) {
                // Always back to the poll, whether or not the assignment has just arrived. Falling
                // through on the poll that granted it would apply the idle window to a consumer
                // that has not yet had a single chance to read the partitions it was given.
                awaitAssignment();
                continue;
            }
            // Quiet. A caught-up feed is the normal case, not an error: the slice simply ends and
            // the next scheduled open resumes from the committed position.
            if (Duration.between(lastRecordAt, clock.instant()).compareTo(idle) >= 0) {
                return false;
            }
        }
    }

    /**
     * Holds the idle window shut until the group has actually given this consumer partitions.
     *
     * <p>Subscribing is not joining. The first polls after {@code subscribe()} return nothing while
     * the group coordinator forms the group -- a broker's {@code group.initial.rebalance.delay.ms}
     * alone is three seconds by default -- and those empty polls look exactly like a quiet topic.
     * With a short idle window the slice then ends before the consumer has been given anything to
     * read, and the run reports landing zero records from a topic full of them. Which is what it
     * did, on the first end-to-end run against a real broker: 250 records published, none landed,
     * exit code 0.
     *
     * <p>So "no records" and "not yet assigned" are separated. Only the first is a quiet topic.
     *
     * @throws IllegalStateException if the group never assigns anything, which is a broken
     *     subscription rather than an empty topic and must not be reported as landing nothing
     */
    private void awaitAssignment() {
        if (!consumer.assignment().isEmpty()) {
            assigned = true;
            // The clock starts here, not at open(). Time spent joining is not time spent idle.
            lastRecordAt = clock.instant();
            return;
        }
        if (Duration.between(openedAt, clock.instant()).compareTo(ASSIGNMENT_TIMEOUT) >= 0) {
            throw new IllegalStateException(
                    "Consumer group did not assign any partition of topic '" + topic + "' within "
                            + ASSIGNMENT_TIMEOUT + ". The broker is reachable but the group never "
                            + "formed; landing nothing would report this as an empty topic.");
        }
    }

    /** Converts one record and records that it is now the caller's, and therefore acknowledgeable. */
    private RawEnvelope emit(ConsumerRecord<byte[], byte[]> record) {
        emitted++;
        yielded.put(
                new TopicPartition(record.topic(), record.partition()),
                new OffsetAndMetadata(record.offset() + 1));
        return KafkaConnector.envelopeOf(record, sourceId, connectorInstanceId, clock.instant());
    }

    /**
     * Commits the offsets of everything handed over so far.
     *
     * <p>Called by {@code LandingService} after a bronze batch commits, and only then. Synchronous
     * on purpose: an asynchronous commit would let the next batch begin before the broker has
     * acknowledged this one, and a crash in that window loses exactly the records the ordering was
     * arranged to protect.
     */
    @Override
    public void acknowledge() {
        if (yielded.isEmpty() || closed) {
            return;
        }
        consumer.commitSync(new HashMap<>(yielded));
    }

    /**
     * Ends the slice, committing nothing.
     *
     * <p>Anything landed has already been acknowledged; anything not is deliberately left
     * uncommitted so it arrives again. Closing is not a landing event, and a commit here would
     * quietly acknowledge a batch that failed on its way to bronze.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        buffered.clear();
        consumer.close();
    }

    /** How many records this slice handed over. */
    int emitted() {
        return emitted;
    }

    @Override
    public String toString() {
        return "KafkaSourceHandle[topic=" + topic + ", emitted=" + emitted + "]";
    }
}
