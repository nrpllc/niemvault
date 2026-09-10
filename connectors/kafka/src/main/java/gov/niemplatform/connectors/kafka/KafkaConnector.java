package gov.niemplatform.connectors.kafka;

import gov.niemplatform.connectors.api.ConnectorConfig;
import gov.niemplatform.connectors.api.ConnectorConfigurationException;
import gov.niemplatform.connectors.api.ConnectorType;
import gov.niemplatform.connectors.api.HealthStatus;
import gov.niemplatform.connectors.api.InteractionMode;
import gov.niemplatform.connectors.api.RetentionPosture;
import gov.niemplatform.connectors.api.SourceConnector;
import gov.niemplatform.connectors.api.SourceHandle;
import gov.niemplatform.storage.api.RawEnvelope;
import gov.niemplatform.storage.api.SourceOffset;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

/**
 * Reads a Kafka topic and lands it (spec §4.3, Phase 2).
 *
 * <p>Like every connector, it knows nothing about the shape of what it carries. A CAD row, a JSON
 * document and a protobuf blob are all just value bytes; parsing them is the mapping's job, which is
 * what lets one mapping serve the same records whether they arrive on a topic or in a file drop.
 *
 * <h2>A slice, not a subscription</h2>
 *
 * <p>The obvious mismatch: a topic has no end, and {@code LandingService} drains a handle to
 * completion. So an {@code open()} here reads a <strong>bounded slice</strong> -- everything
 * currently available, up to {@code maxRecords}, stopping once the topic has been quiet for
 * {@code idleMillis} -- and returns. It is a consumer group, so the next {@code open()} resumes
 * where this one stopped, and a scheduled ingest that runs every minute makes a continuous feed out
 * of repeated bounded reads.
 *
 * <p>This is a deliberate boundary rather than a limitation of the design. A genuinely unbounded
 * landing loop would have to make its own decisions about commit cadence, back-pressure and
 * shutdown, and every one of those would be a second answer to a question {@code LandingService}
 * already answers for every other transport. Spec §4.3 requires transport differences not to leak
 * past the landing boundary; an endless stream would leak the largest one there is.
 *
 * <h2>What arrives at least once, and never less</h2>
 *
 * <p>Offsets are committed in {@link SourceHandle#acknowledge()}, which {@code LandingService} calls
 * only after a bronze batch has committed. A crash therefore redelivers rather than drops. The
 * redelivered records land with the identity they had the first time -- envelope identity is derived
 * from source, offset and content hash, and a Kafka offset is stable -- so a duplicate is exactly
 * detectable rather than merely likely.
 *
 * <p>Auto-commit is switched off explicitly, and not as a tuning choice: a consumer that commits on
 * its own schedule can acknowledge records bronze never received, which is the one failure this
 * whole arrangement exists to prevent.
 *
 * <h2>Settings</h2>
 *
 * <table>
 *   <caption>Transport settings</caption>
 *   <tr><th>Key</th><th>Meaning</th></tr>
 *   <tr><td>{@code bootstrapServers}</td><td>Required. {@code host:port[,host:port]}.</td></tr>
 *   <tr><td>{@code topic}</td><td>Required. The topic to read.</td></tr>
 *   <tr><td>{@code groupId}</td><td>Required. Consumer group; this is the read position.</td></tr>
 *   <tr><td>{@code retention}</td><td>Required, no default. {@code retained} or {@code transient}.</td></tr>
 *   <tr><td>{@code autoOffsetReset}</td><td>{@code earliest} (default) or {@code latest}.</td></tr>
 *   <tr><td>{@code maxRecords}</td><td>Ceiling on one slice. Default {@code 100000}.</td></tr>
 *   <tr><td>{@code idleMillis}</td><td>Quiet period that ends a slice. Default {@code 5000}.</td></tr>
 *   <tr><td>{@code pollMillis}</td><td>One poll's wait. Default {@code 500}.</td></tr>
 *   <tr><td>{@code securityProtocol}</td><td>Optional, e.g. {@code SASL_SSL}.</td></tr>
 *   <tr><td>{@code saslMechanism}</td><td>Optional, e.g. {@code SCRAM-SHA-512}.</td></tr>
 *   <tr><td>{@code saslJaasConfig}</td><td>Optional JAAS string. Never logged.</td></tr>
 * </table>
 */
public final class KafkaConnector implements SourceConnector {

    /** Transport identifier, as used in connector configuration. */
    public static final ConnectorType TYPE = ConnectorType.of("kafka");

    static final String SETTING_BOOTSTRAP_SERVERS = "bootstrapServers";
    static final String SETTING_TOPIC = "topic";
    static final String SETTING_GROUP_ID = "groupId";
    static final String SETTING_RETENTION = "retention";
    static final String SETTING_AUTO_OFFSET_RESET = "autoOffsetReset";
    static final String SETTING_MAX_RECORDS = "maxRecords";
    static final String SETTING_IDLE_MILLIS = "idleMillis";
    static final String SETTING_POLL_MILLIS = "pollMillis";
    static final String SETTING_SECURITY_PROTOCOL = "securityProtocol";
    static final String SETTING_SASL_MECHANISM = "saslMechanism";
    static final String SETTING_SASL_JAAS_CONFIG = "saslJaasConfig";

    private static final Set<String> RECOGNISED_SETTINGS = Set.of(
            SETTING_BOOTSTRAP_SERVERS, SETTING_TOPIC, SETTING_GROUP_ID, SETTING_RETENTION,
            SETTING_AUTO_OFFSET_RESET, SETTING_MAX_RECORDS, SETTING_IDLE_MILLIS, SETTING_POLL_MILLIS,
            SETTING_SECURITY_PROTOCOL, SETTING_SASL_MECHANISM, SETTING_SASL_JAAS_CONFIG);

    /** How long a health probe waits before calling the broker unreachable. */
    private static final Duration HEALTH_PROBE_TIMEOUT = Duration.ofSeconds(10);

    private final Clock clock;

    private ConnectorConfig config;
    private String topic;
    private RetentionPosture retention;
    private int maxRecords;
    private Duration idle;
    private Duration poll;
    private Properties consumerProperties;

    public KafkaConnector() {
        this(Clock.systemUTC());
    }

    public KafkaConnector(Clock clock) {
        this.clock = clock;
    }

    @Override
    public ConnectorType type() {
        return TYPE;
    }

    @Override
    public InteractionMode interactionMode() {
        // The source publishes when it has something; the platform does not choose the moment.
        // That a slice is read on a schedule is how this connector is driven, not how records
        // arrive -- the records were already sitting on the topic, put there by someone else.
        return InteractionMode.PUSH;
    }

    /**
     * Whether records from this topic may be kept -- configured, never assumed.
     *
     * <p>The file drop connector can answer this from the transport alone: a file an agency placed
     * in its own drop directory is its own data. Kafka cannot. The same broker, the same connector
     * and the same code carry an agency's own CAD feed on one topic and a state system's criminal
     * history responses on another, and only one of those may be landed (ADR 0027).
     *
     * <p>So the answer comes from configuration, and configuration has no default. A transport that
     * can carry either kind must be told which it is carrying, because the alternative -- assuming
     * {@code RETAINED}, which is the only assumption that would let a run proceed -- is an unlawful
     * retention arrived at by omission.
     *
     * @throws IllegalStateException before {@code configure}, rather than guessing
     */
    @Override
    public RetentionPosture retention() {
        if (retention == null) {
            throw new IllegalStateException(
                    "KafkaConnector.retention() called before configure(). A Kafka topic's retention "
                            + "posture is configuration, not a property of the transport: the same "
                            + "broker carries an agency's own feed and a state system's "
                            + "non-retainable responses. Set the '" + SETTING_RETENTION + "' "
                            + "setting (ADR 0027).");
        }
        return retention;
    }

    @Override
    public void configure(ConnectorConfig connectorConfig) {
        connectorConfig.requireOnly(RECOGNISED_SETTINGS);

        List<ConnectorConfigurationException.Problem> problems = new ArrayList<>();

        String bootstrap = connectorConfig.requiredSetting(SETTING_BOOTSTRAP_SERVERS);
        String configuredTopic = connectorConfig.requiredSetting(SETTING_TOPIC);
        String groupId = connectorConfig.requiredSetting(SETTING_GROUP_ID);

        RetentionPosture configuredRetention = readRetention(connectorConfig, problems);

        String offsetReset = connectorConfig
                .settingOr(SETTING_AUTO_OFFSET_RESET, "earliest").toLowerCase(Locale.ROOT);
        if (!offsetReset.equals("earliest") && !offsetReset.equals("latest")) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_AUTO_OFFSET_RESET,
                    "must be 'earliest' or 'latest', found '" + offsetReset + "'"));
        }

        int configuredMaxRecords = connectorConfig.intSettingOr(SETTING_MAX_RECORDS, 100_000);
        if (configuredMaxRecords <= 0) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_MAX_RECORDS,
                    "must be positive, found " + configuredMaxRecords));
        }
        int configuredIdle = connectorConfig.intSettingOr(SETTING_IDLE_MILLIS, 5_000);
        if (configuredIdle <= 0) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_IDLE_MILLIS,
                    "must be positive, found " + configuredIdle));
        }
        int configuredPoll = connectorConfig.intSettingOr(SETTING_POLL_MILLIS, 500);
        if (configuredPoll <= 0) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_POLL_MILLIS,
                    "must be positive, found " + configuredPoll));
        }

        if (!problems.isEmpty()) {
            throw new ConnectorConfigurationException(
                    connectorConfig.sourceId(), connectorConfig.connectorInstanceId(), problems);
        }

        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetReset);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        // Explicit, and not a tuning choice. A consumer committing on its own schedule can
        // acknowledge records bronze never received, which is the failure the acknowledge-after-
        // commit ordering exists to prevent. Turning this back on would silently undo it.
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, Integer.toString(
                Math.min(configuredMaxRecords, 500)));
        connectorConfig.setting(SETTING_SECURITY_PROTOCOL)
                .ifPresent(value -> properties.put("security.protocol", value));
        connectorConfig.setting(SETTING_SASL_MECHANISM)
                .ifPresent(value -> properties.put("sasl.mechanism", value));
        connectorConfig.setting(SETTING_SASL_JAAS_CONFIG)
                .ifPresent(value -> properties.put("sasl.jaas.config", value));

        this.config = connectorConfig;
        this.topic = configuredTopic;
        this.retention = configuredRetention;
        this.maxRecords = configuredMaxRecords;
        this.idle = Duration.ofMillis(configuredIdle);
        this.poll = Duration.ofMillis(configuredPoll);
        this.consumerProperties = properties;
    }

    /**
     * Reads the retention posture, which must be stated.
     *
     * <p>Reported as a configuration problem alongside the others rather than thrown on its own, so
     * an operator who has also misspelled a setting fixes both in one pass (spec §9).
     */
    private RetentionPosture readRetention(
            ConnectorConfig connectorConfig, List<ConnectorConfigurationException.Problem> problems) {

        String declared = connectorConfig.setting(SETTING_RETENTION).orElse(null);
        if (declared == null) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_RETENTION,
                    "is required and has no default. A Kafka topic may carry this agency's own data "
                            + "('retained') or another system's non-retainable response "
                            + "('transient'), and the transport cannot tell which. Defaulting would "
                            + "land data that may not lawfully be kept (ADR 0027)"));
            return null;
        }
        return switch (declared.toLowerCase(Locale.ROOT)) {
            case "retained" -> RetentionPosture.RETAINED;
            case "transient" -> RetentionPosture.TRANSIENT;
            default -> {
                problems.add(new ConnectorConfigurationException.Problem(SETTING_RETENTION,
                        "must be 'retained' or 'transient', found '" + declared + "'"));
                yield null;
            }
        };
    }

    /**
     * Opens a bounded slice of the topic.
     *
     * <p>A fresh consumer per slice, deliberately. A consumer is not thread-safe and holds a group
     * membership; keeping one alive between reads would mean a connector idle in a CronJob was still
     * a live group member, and a rebalance during a run it was not part of.
     */
    @Override
    public SourceHandle open() {
        requireConfigured();
        KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(consumerProperties);
        try {
            consumer.subscribe(List.of(topic));
        } catch (RuntimeException e) {
            consumer.close();
            throw e;
        }
        return new KafkaSourceHandle(
                consumer, config.sourceId(), config.connectorInstanceId(), topic,
                maxRecords, idle, poll, clock);
    }

    /**
     * Whether the broker is reachable and the topic exists.
     *
     * <p>Distinct from whether records are flowing, per {@code HealthStatus}: a topic with no
     * unread records is healthy and idle. A topic that does not exist is not, and saying so is the
     * difference between a misconfigured source and a quiet one.
     */
    @Override
    public HealthStatus health() {
        Instant now = clock.instant();
        if (config == null) {
            return HealthStatus.notConfigured(now);
        }
        Properties probeProperties = new Properties();
        probeProperties.putAll(consumerProperties);
        // A probe must not join the group it is probing: doing so triggers a rebalance of whatever
        // is actually reading, so a health check would disrupt the ingest it is checking on.
        probeProperties.put(ConsumerConfig.GROUP_ID_CONFIG,
                consumerProperties.getProperty(ConsumerConfig.GROUP_ID_CONFIG) + "-health-probe");
        try (KafkaConsumer<byte[], byte[]> probe = new KafkaConsumer<>(probeProperties)) {
            List<PartitionInfo> partitions = probe.partitionsFor(topic, HEALTH_PROBE_TIMEOUT);
            if (partitions == null || partitions.isEmpty()) {
                return HealthStatus.degraded(
                        "topic '" + topic + "' has no partitions on this broker; it may not exist", now);
            }
            return HealthStatus.healthy(now);
        } catch (RuntimeException e) {
            // The message, never the settings: a bootstrap string routinely carries credentials.
            return HealthStatus.unavailable(
                    "cannot reach the broker for topic '" + topic + "': " + e.getMessage(), now);
        }
    }

    @Override
    public void close() {
        // Nothing held open between reads: a consumer's life is one open() slice.
    }

    private void requireConfigured() {
        if (config == null) {
            throw new IllegalStateException("KafkaConnector.open() called before configure()");
        }
    }

    /**
     * Where a record sat on the topic: {@code <topic>-<partition>:<offset>}.
     *
     * <p>Zero-padded because {@code SourceOffset} orders lexicographically and a bronze range is
     * expressed in those terms. Unpadded, offset 9 would sort after offset 10, and a replay range
     * would silently cover the wrong records. Nineteen digits is the width of {@link Long#MAX_VALUE},
     * so no offset a broker can produce overflows it.
     */
    static SourceOffset offsetOf(ConsumerRecord<byte[], byte[]> record) {
        return SourceOffset.of("%s-%06d:%019d".formatted(
                record.topic(), record.partition(), record.offset()));
    }

    /**
     * When the source says the record happened.
     *
     * <p>Only when the broker holds a <em>create</em> time, which is the producer's assertion about
     * the event. A log-append time is the broker's own clock stamping arrival -- that is ingest
     * time wearing the wrong label, and treating it as a source assertion would make
     * {@code PipelineLag} (§4.7) measure the platform against itself and report a permanently
     * healthy feed.
     */
    static Instant assertedTimestamp(ConsumerRecord<byte[], byte[]> record) {
        if (record.timestampType() != TimestampType.CREATE_TIME
                || record.timestamp() < 0) {
            return null;
        }
        return Instant.ofEpochMilli(record.timestamp());
    }

    /**
     * The value bytes, byte-preserved.
     *
     * <p>A tombstone -- a record with a null value -- lands as an empty payload rather than being
     * skipped. It is a real thing the source said, and a connector that silently dropped records
     * would break the completeness accounting that exists to prove nothing goes missing.
     *
     * <p>The key is not part of the payload. It is transport addressing, like a filename: partition
     * routing and compaction identity. A key that carries meaning the mapping needs is a source
     * putting data somewhere the record cannot see, and the fix is in the producer, not here.
     */
    static byte[] payloadOf(ConsumerRecord<byte[], byte[]> record) {
        byte[] value = record.value();
        return value == null ? new byte[0] : value;
    }

    /** Builds an envelope from a consumed record. Package-private so its shape can be tested. */
    static RawEnvelope envelopeOf(
            ConsumerRecord<byte[], byte[]> record, String sourceId, String instanceId, Instant ingestedAt) {
        return new RawEnvelope(
                sourceId, instanceId, ingestedAt, assertedTimestamp(record),
                payloadOf(record), offsetOf(record));
    }

    /** Only what an operator needs, and no settings. A bootstrap string routinely carries a secret. */
    @Override
    public String toString() {
        return "KafkaConnector[topic=" + (topic == null ? "<unconfigured>" : topic)
                + ", retention=" + (retention == null ? "<undeclared>" : retention) + "]";
    }
}
