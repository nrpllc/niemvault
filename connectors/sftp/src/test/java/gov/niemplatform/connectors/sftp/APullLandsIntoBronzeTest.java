package gov.niemplatform.connectors.sftp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.connectors.api.ConnectorConfig;
import gov.niemplatform.connectors.api.ConnectorConfigurationException;
import gov.niemplatform.connectors.api.LandingService;
import gov.niemplatform.connectors.api.SourceCheckpointStore;
import gov.niemplatform.connectors.pull.AfterDownload;
import gov.niemplatform.observability.RecordingObservabilityEmitter;
import gov.niemplatform.storage.api.BronzeBatch;
import gov.niemplatform.storage.api.BronzeBatchReceipt;
import gov.niemplatform.storage.api.BronzeRange;
import gov.niemplatform.storage.api.BronzeStore;
import gov.niemplatform.storage.api.RawEnvelope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pulled directory lands in bronze like any other source, through the one path that does it.
 *
 * <p>The connector's own tests drive it directly, which is the right way to test a transport and the
 * wrong way to find out what happens when {@code LandingService} drives it: batching, the
 * acknowledge-after-commit ordering, and the retention refusal all live there, and none of them is
 * exercised by a test that drains a handle itself.
 *
 * <p>The interesting case is the failed commit. Spec §4.3 requires transport differences not to leak
 * past the landing boundary, and ADR 0028's ordering is what makes a crash recoverable -- but for a
 * pull the consequence is physical: a file archived on the strength of a commit that did not happen
 * is a file no later run can find.
 */
class APullLandsIntoBronzeTest {

    private static final Instant NOW = Instant.parse("2026-03-04T11:20:00Z");
    private static final Instant MONDAY = Instant.parse("2026-03-02T06:00:00Z");
    private static final String SOURCE_ID = "riverton-pd-cad";
    private static final String INSTANCE_ID = "cad-sftp-1";

    private static final List<String> ROWS = List.of(
            "2026-000114,BURG,2026/03/04 11:20,\"418 W 9TH ST\",3A,VICT,\"DOE, JANE M\"",
            "2026-000114,BURG,2026/03/04 11:20,\"418 W 9TH ST\",3A,WITN,\"NAKAMURA, HIRO\"",
            "2026-000115,ASSLT,2026/03/04 12:05,\"22 ELM AVE\",2B,SUSP,\"RIVERA, LUIS\"",
            "2026-000115,ASSLT,2026/03/04 12:05,\"22 ELM AVE\",2B,VICT,\"O'BRIEN, SEAN P\"");

    @TempDir
    Path remote;

    private EmbeddedSftpServer server;
    private SourceCheckpointStore checkpoints;

    @BeforeEach
    void startServer() throws IOException {
        server = EmbeddedSftpServer.serving(remote);
        checkpoints = SourceCheckpointStore.inMemory();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.close();
    }

    /** Collects what landed, and can be told to fail, which is the interesting half. */
    private static final class CollectingStore implements BronzeStore {

        private final List<RawEnvelope> landed = new ArrayList<>();
        private final Map<String, BronzeBatchReceipt> receipts = new LinkedHashMap<>();
        private final int failOnBatch;
        private int batches;

        CollectingStore() {
            this(0);
        }

        CollectingStore(int failOnBatch) {
            this.failOnBatch = failOnBatch;
        }

        @Override
        public BronzeBatchReceipt append(BronzeBatch batch) {
            batches++;
            if (batches == failOnBatch) {
                throw new IllegalStateException("bronze is unavailable");
            }
            landed.addAll(batch.envelopes());
            String batchId = "batch-%04d".formatted(batches);
            List<RawEnvelope> envelopes = batch.envelopes();
            BronzeBatchReceipt receipt = new BronzeBatchReceipt(
                    batchId, batch.sourceId(), batch.connectorInstanceId(), envelopes.size(), NOW,
                    envelopes.getFirst().offset(), envelopes.getLast().offset(),
                    "data/" + batchId + ".parquet", "data/" + batchId + ".json");
            receipts.put(batchId, receipt);
            return receipt;
        }

        @Override
        public List<BronzeBatchReceipt> batches(String sourceId) {
            return List.copyOf(receipts.values());
        }

        @Override
        public Stream<RawEnvelope> read(String sourceId, BronzeRange range) {
            return landed.stream();
        }

        @Override
        public List<String> sources() {
            return landed.isEmpty() ? List.of() : List.of(landed.getFirst().sourceId());
        }

        @Override
        public void close() {
            // Nothing held.
        }
    }

    @Test
    @DisplayName("a pulled file reaches bronze with its records in source order")
    void landsThroughTheLandingService() throws IOException {
        server.give("cad-2026-03-02.csv", String.join("\n", ROWS) + "\n", MONDAY);
        CollectingStore bronze = new CollectingStore();
        SftpConnector connector = connectorWith(Map.of("afterDownload", "archive",
                "archiveDirectory", "/archive"));
        Files.createDirectories(remote.resolve("archive"));

        LandingService.LandingResult result = land(connector, bronze);

        assertThat(result.recordsLanded()).isEqualTo(4);
        assertThat(bronze.landed)
                .extracting(envelope -> new String(envelope.payload(), StandardCharsets.UTF_8))
                .containsExactlyElementsOf(ROWS);
        // The source-asserted time reached bronze, so freshness is measured against the export and
        // not against the run that happened to pick it up.
        assertThat(result.latestSourceAssertion()).contains(MONDAY);
        assertThat(remote.resolve("archive/cad-2026-03-02.csv")).exists();
    }

    /**
     * The ordering ADR 0028 exists for, with a pull's physical consequence.
     *
     * <p>Acknowledging before the commit would archive a file whose records bronze never received.
     * The file is then out of the drop directory, no later run lists it, and nothing anywhere
     * reports that those records are missing -- there is no record of them to be missing from.
     */
    @Test
    @DisplayName("a failed commit archives nothing, so the file is still there to be re-read")
    void aFailedCommitLeavesTheFile() throws IOException {
        Files.createDirectories(remote.resolve("archive"));
        server.give("cad-2026-03-02.csv", String.join("\n", ROWS) + "\n", MONDAY);
        CollectingStore bronze = new CollectingStore(1);
        SftpConnector connector = connectorWith(Map.of(
                "afterDownload", "archive",
                "archiveDirectory", "/archive"));

        assertThatThrownBy(() -> land(connector, bronze))
                .isInstanceOf(IllegalStateException.class);

        assertThat(remote.resolve("cad-2026-03-02.csv")).exists();
        assertThat(remote.resolve("archive/cad-2026-03-02.csv")).doesNotExist();

        // And the re-read lands the whole file, because nothing was acknowledged.
        assertThat(land(connector, new CollectingStore()).recordsLanded()).isEqualTo(4);
    }

    /** Same property for the posture whose position lives in this platform rather than the server. */
    @Test
    @DisplayName("a failed commit advances no watermark")
    void aFailedCommitAdvancesNoWatermark() throws IOException {
        server.give("cad-2026-03-02.csv", String.join("\n", ROWS) + "\n", MONDAY);
        SftpConnector connector = connectorWith(Map.of("afterDownload", "watermark"));

        assertThatThrownBy(() -> land(connector, new CollectingStore(1)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(checkpoints.read(SOURCE_ID, INSTANCE_ID)).isEmpty();
        assertThat(land(connector, new CollectingStore()).recordsLanded()).isEqualTo(4);
    }

    @Test
    @DisplayName("batching does not change what lands, only how many commits it takes")
    void batchingIsInvisible() throws IOException {
        server.give("cad-2026-03-02.csv", String.join("\n", ROWS) + "\n", MONDAY);
        CollectingStore bronze = new CollectingStore();
        SftpConnector connector = connectorWith(Map.of("afterDownload", "delete"));

        LandingService.LandingResult result = new LandingService(
                bronze, new RecordingObservabilityEmitter(), Clock.fixed(NOW, ZoneOffset.UTC), 2)
                .land(connector, config(connector), "run-1");

        assertThat(result.recordsLanded()).isEqualTo(4);
        assertThat(result.receipts()).hasSize(2);
        assertThat(bronze.landed).hasSize(4);
        assertThat(remote.resolve("cad-2026-03-02.csv")).doesNotExist();
    }

    /**
     * A transient source is refused before it is opened, which for a pull means before a connection
     * is made at all -- so a directory that may not be landed is never even listed.
     */
    @Test
    @DisplayName("a non-retainable pull is refused rather than landed and filtered")
    void aTransientPullIsNotLanded() throws IOException {
        server.give("responses.csv", "whatever\n", MONDAY);
        SftpConnector connector = connectorWith(Map.of(
                "afterDownload", "none",
                "retention", "transient"));

        assertThatThrownBy(() -> land(connector, new CollectingStore()))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("may not be retained");

        assertThat(remote.resolve("responses.csv")).exists();
    }

    private LandingService.LandingResult land(SftpConnector connector, CollectingStore bronze) {
        return new LandingService(bronze, new RecordingObservabilityEmitter())
                .land(connector, config(connector), "run-1");
    }

    private ConnectorConfig config(SftpConnector connector) {
        return ConnectorConfig.of(SOURCE_ID, INSTANCE_ID, SftpConnector.TYPE, settings(Map.of()));
    }

    private SftpConnector connectorWith(Map<String, String> extra) {
        SftpConnector connector = new SftpConnector(Clock.fixed(NOW, ZoneOffset.UTC));
        connector.useCheckpointStore(checkpoints);
        connector.configure(ConnectorConfig.of(
                SOURCE_ID, INSTANCE_ID, SftpConnector.TYPE, settings(extra)));
        return connector;
    }

    private Map<String, String> settings(Map<String, String> extra) {
        Map<String, String> settings = new HashMap<>(Map.of(
                "host", "127.0.0.1",
                "port", Integer.toString(server.port()),
                "username", EmbeddedSftpServer.USERNAME,
                "password", EmbeddedSftpServer.PASSWORD,
                "hostKeyCheck", "accept-any",
                "directory", "/",
                "retention", "retained"));
        settings.putAll(extra);
        return settings;
    }
}
