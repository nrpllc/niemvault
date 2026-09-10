package gov.niemplatform.cli;

import gov.niemplatform.modules.le.CadSimulator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Publishes a synthetic CAD feed to a Kafka topic.
 *
 * <p>What this is for: the file drop connector could be exercised with a file, and a streaming
 * connector cannot. Proving that a topic lands, that a slice resumes where the last one stopped,
 * that identity resolution meets the same human again an hour later, and that a source drifting
 * mid-flight is caught rather than absorbed -- none of it is demonstrable without something
 * producing records while the platform reads them.
 *
 * <p><strong>Every record is invented.</strong> The generator is a seeded pseudo-random source with
 * no path by which real data could reach it (ADR 0013); see {@link CadSimulator}.
 *
 * <p>Deliberately a separate command from {@code run}, and not a mode of it. A simulator that could
 * be switched on inside an ingest is one that can be switched on by accident against a real bronze
 * store, and synthetic records mixed into an agency's landed data cannot be taken back out.
 */
@Command(
        name = "simulate",
        mixinStandardHelpOptions = true,
        description = "Publish a synthetic CAD feed to a Kafka topic, for exercising a live ingest.")
final class SimulateCommand implements Callable<Integer> {

    @Option(names = "--bootstrap", required = true,
            description = "Kafka bootstrap servers, e.g. localhost:9092.")
    String bootstrapServers;

    @Option(names = "--topic", required = true, description = "Topic to publish to.")
    String topic;

    @Option(names = "--count", defaultValue = "500",
            description = "Records to publish. Default: ${DEFAULT-VALUE}")
    int count;

    @Option(names = "--rate", defaultValue = "0",
            description = "Records per second. 0 publishes as fast as the broker accepts, which is "
                    + "what a backlog looks like; a rate is what a live feed looks like. "
                    + "Default: ${DEFAULT-VALUE}")
    double rate;

    @Option(names = "--seed", defaultValue = "42",
            description = "Feed seed. The same seed produces the same records. Default: ${DEFAULT-VALUE}")
    long seed;

    @Option(names = "--drift-after",
            description = "Switch the source to its drifted shape after this many records, so a "
                    + "contract violation happens mid-feed rather than at the start.")
    Integer driftAfter;

    @Option(names = "--feed-start",
            description = "ISO-8601 instant the simulated calls start at. Default: now, to the "
                    + "minute. Pin it to reproduce a feed byte for byte.")
    String feedStart;

    @Option(names = "--partitions", defaultValue = "1",
            description = "Partitions to create the topic with, if it does not exist. "
                    + "Default: ${DEFAULT-VALUE}")
    int partitions;

    @Option(names = "--no-create-topic",
            description = "Fail rather than create the topic. Use where topic creation is governed.")
    boolean noCreateTopic;

    @Override
    public Integer call() throws Exception {
        if (count <= 0) {
            System.err.println("--count must be positive.");
            return 1;
        }
        if (driftAfter != null && driftAfter < 0) {
            System.err.println("--drift-after cannot be negative.");
            return 1;
        }

        if (!noCreateTopic && !ensureTopic()) {
            return 1;
        }

        Instant start;
        try {
            start = feedStartInstant();
        } catch (java.time.format.DateTimeParseException e) {
            System.err.println("--feed-start must be an ISO-8601 instant such as "
                    + "2026-09-09T14:00:00Z, found '" + feedStart + "'.");
            return 1;
        }
        int cleanRecords = driftAfter == null ? count : Math.min(driftAfter, count);

        long published = 0;
        try (Producer<byte[], byte[]> producer = new KafkaProducer<>(producerProperties())) {
            published += publish(producer, CadSimulator.Shape.CLEAN, seed, start, cleanRecords, 0);

            if (cleanRecords < count) {
                System.out.printf("%n-- source drifts here: the same feed, a changed shape --%n");
                // A separate generator continuing the incident sequence, so the drift lands in the
                // middle of a feed rather than restarting it. The mapping does not change; the
                // source does, which is the whole point of criterion 5.
                published += publish(producer, CadSimulator.Shape.DRIFTED, seed + 1,
                        start.plus(Duration.ofMinutes(7L * cleanRecords)),
                        count - cleanRecords, cleanRecords);
            }
            producer.flush();
        }

        System.out.printf("%nPublished %d record(s) to '%s'.%n", published, topic);
        System.out.println("Land them with: niem run --source <source.yaml> ...");
        return 0;
    }

    /**
     * When the simulated calls are reported, defaulting to now.
     *
     * <p>Not a fixed date, and this is not cosmetic. The record timestamp serves two masters: the
     * platform reads it as what the source asserts (§4.7 measures freshness against it), and
     * <em>Kafka reads it for retention</em>. A feed backdated past the topic's retention window is
     * deleted by the broker as fast as it is written -- the log start offset jumps to the end, and a
     * consumer reading from {@code earliest} correctly finds nothing.
     *
     * <p>Which is exactly what happened the first time this was run end to end: 250 records
     * published, 250 records expired, zero landed, and every log line healthy. A fixed date is fine
     * in a fixture file and wrong on a broker.
     *
     * <p>Truncated to the minute, and pinnable with {@code --feed-start}, so a run can still be
     * reproduced byte for byte when that is what is wanted.
     */
    private Instant feedStartInstant() {
        return feedStart == null
                ? Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
                : Instant.parse(feedStart);
    }

    /**
     * Publishes one stretch of the feed.
     *
     * @param offsetInFeed how many records already went before this stretch, for the progress line
     * @return how many records were published
     */
    private long publish(
            Producer<byte[], byte[]> producer,
            CadSimulator.Shape shape,
            long feedSeed,
            Instant feedStart,
            int records,
            int offsetInFeed) throws InterruptedException {

        long intervalNanos = rate > 0 ? (long) (1_000_000_000L / rate) : 0;
        long published = 0;

        try (Stream<CadSimulator.Row> rows = new CadSimulator(feedSeed, shape, feedStart).rows()) {
            for (CadSimulator.Row row : (Iterable<CadSimulator.Row>) rows.limit(records)::iterator) {
                producer.send(new ProducerRecord<>(
                        topic,
                        null,
                        // The record timestamp is the moment the source says the call was reported,
                        // not the moment it was published. The connector reads CREATE_TIME as the
                        // source assertion, and PipelineLag (§4.7) is measured against it -- a
                        // publish-time timestamp would make every feed permanently on time.
                        row.reportedAt().toEpochMilli(),
                        // Keyed by incident so every row of one incident lands on one partition and
                        // stays in order. Unkeyed, the people on an incident could be split across
                        // partitions and read out of order.
                        row.incidentNumber().getBytes(StandardCharsets.UTF_8),
                        row.csv().getBytes(StandardCharsets.UTF_8)));
                published++;

                long total = offsetInFeed + published;
                if (total % 100 == 0 || total == count) {
                    System.out.printf("  %d/%d published (%s)%n", total, count, shape);
                }
                if (intervalNanos > 0) {
                    Thread.sleep(Duration.ofNanos(intervalNanos));
                }
            }
        }
        return published;
    }

    /**
     * Creates the topic if it is not already there.
     *
     * <p>Convenience for a demonstration, not a policy: a deployment where topic creation is
     * governed passes {@code --no-create-topic} and the topic is made by whoever owns it.
     *
     * @return whether the topic exists and can be published to
     */
    private boolean ensureTopic() {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", bootstrapServers);
        try (Admin admin = Admin.create(properties)) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get();
            System.out.printf("Created topic '%s' with %d partition(s).%n", topic, partitions);
            return true;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TopicExistsException) {
                System.out.printf("Topic '%s' already exists; publishing to it.%n", topic);
                return true;
            }
            System.err.println("Cannot create topic '" + topic + "': " + e.getCause().getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while creating topic '" + topic + "'.");
            return false;
        }
    }

    private Properties producerProperties() {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        // Every replica, not just the leader. A simulator that lost records on a broker restart
        // would make the platform's completeness accounting look wrong when it was right.
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        return properties;
    }
}
