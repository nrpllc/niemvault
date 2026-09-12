package gov.niemplatform.connectors.sftp;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.connectors.api.ConnectorConfig;
import gov.niemplatform.connectors.api.SourceCheckpointStore;
import gov.niemplatform.connectors.api.SourceHandle;
import gov.niemplatform.connectors.pull.AfterDownload;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pull does not re-read what it has already landed, and does not skip what it has not (ADR 0031).
 *
 * <p>The question a pull transport exists to answer and a push transport never faces. Kafka hands
 * its position to a consumer group; a remote directory remembers nothing, so the four
 * {@link AfterDownload} postures differ in who remembers and in what each can get wrong. These
 * tests run against a real SFTP server, because the failures worth catching are in the protocol.
 */
class APullRemembersWhatItLandedTest {

    private static final Instant NOW = Instant.parse("2026-03-04T11:20:00Z");
    private static final Instant MONDAY = Instant.parse("2026-03-02T06:00:00Z");
    private static final Instant TUESDAY = Instant.parse("2026-03-03T06:00:00Z");
    private static final Instant WEDNESDAY = Instant.parse("2026-03-04T06:00:00Z");

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

    @Test
    @DisplayName("a line-mode pull lands one record per line, stamped with the file's own time")
    void landsLines() throws IOException {
        server.give("cad-2026-03-02.csv", "id,kind\n1,theft\n2,assault\n", MONDAY);
        SftpConnector connector = connectorWith(Map.of(
                "afterDownload", "none",
                "skipHeaderLines", "1"));

        List<RawEnvelope> landed = pull(connector);

        assertThat(landed).hasSize(2);
        assertThat(new String(landed.getFirst().payload(), StandardCharsets.UTF_8)).isEqualTo("1,theft");
        assertThat(landed.getFirst().offset().value()).isEqualTo("cad-2026-03-02.csv#000002");
        // The source-asserted time is the file's, not the run's. A pull that landed day-old records
        // is on time by ingest time and a day stale by the only measure §4.7 cares about.
        assertThat(landed.getFirst().sourceAssertedTimestamp()).contains(MONDAY);
    }

    @Test
    @DisplayName("a file-mode pull lands one record per file")
    void landsWholeFiles() throws IOException {
        server.give("incident.json", "{\"id\":1}", MONDAY);
        SftpConnector connector = connectorWith(Map.of(
                "afterDownload", "none",
                "recordMode", "file"));

        List<RawEnvelope> landed = pull(connector);

        assertThat(landed).hasSize(1);
        assertThat(new String(landed.getFirst().payload(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":1}");
        assertThat(landed.getFirst().offset().value()).isEqualTo("incident.json");
    }

    @Test
    @DisplayName("files are read oldest first, so the order they land in is the order they arrived")
    void readsInTimeOrder() throws IOException {
        // Named so that name order and time order disagree: name alone would read these backwards.
        server.give("zebra.csv", "z\n", MONDAY);
        server.give("alpha.csv", "a\n", WEDNESDAY);
        SftpConnector connector = connectorWith(Map.of("afterDownload", "none"));

        List<RawEnvelope> landed = pull(connector);

        assertThat(landed).extracting(envelope -> envelope.offset().value())
                .containsExactly("zebra.csv#000001", "alpha.csv#000001");
    }

    @Test
    @DisplayName("only files matching the pattern are pulled")
    void honoursThePattern() throws IOException {
        server.give("cad.csv", "1\n", MONDAY);
        server.give("notes.txt", "ignore me\n", MONDAY);
        SftpConnector connector = connectorWith(Map.of(
                "afterDownload", "none",
                "filePattern", "*.csv"));

        assertThat(pull(connector)).hasSize(1);
    }

    /** The archive directory the connector creates entries in must not be pulled as a source file. */
    @Test
    @DisplayName("a subdirectory in the drop directory is skipped rather than failing the pull")
    void skipsDirectories() throws IOException {
        server.give("cad.csv", "1\n", MONDAY);
        Files.createDirectories(remote.resolve("archive"));
        SftpConnector connector = connectorWith(Map.of("afterDownload", "none"));

        assertThat(pull(connector)).hasSize(1);
    }

    @Test
    @DisplayName("with afterDownload none, a second run re-reads everything")
    void noneRereads() throws IOException {
        server.give("cad.csv", "1\n2\n", MONDAY);
        SftpConnector connector = connectorWith(Map.of("afterDownload", "none"));

        assertThat(pull(connector)).hasSize(2);
        assertThat(pull(connector)).hasSize(2);
    }

    @Test
    @DisplayName("with a watermark, a second run reads only what arrived since")
    void watermarkAdvances() throws IOException {
        server.give("cad-mon.csv", "mon\n", MONDAY);
        SftpConnector connector = connectorWith(Map.of("afterDownload", "watermark"));

        assertThat(pull(connector)).hasSize(1);
        assertThat(pull(connector)).isEmpty();

        server.give("cad-tue.csv", "tue\n", TUESDAY);
        List<RawEnvelope> second = pull(connector);

        assertThat(second).hasSize(1);
        assertThat(new String(second.getFirst().payload(), StandardCharsets.UTF_8)).isEqualTo("tue");
    }

    @Test
    @DisplayName("a watermark survives a new connector, which is the restart it exists for")
    void watermarkSurvivesRestart() throws IOException {
        server.give("cad-mon.csv", "mon\n", MONDAY);
        assertThat(pull(connectorWith(Map.of("afterDownload", "watermark")))).hasSize(1);

        // A fresh connector object over the same checkpoint store is what a restarted process gets.
        assertThat(pull(connectorWith(Map.of("afterDownload", "watermark")))).isEmpty();
    }

    @Test
    @DisplayName("archiving moves a landed file, so the next run does not see it")
    void archiveMoves() throws IOException {
        Files.createDirectories(remote.resolve("archive"));
        server.give("cad.csv", "1\n", MONDAY);
        SftpConnector connector = connectorWith(Map.of(
                "afterDownload", "archive",
                "archiveDirectory", "/archive"));

        assertThat(pull(connector)).hasSize(1);

        assertThat(remote.resolve("cad.csv")).doesNotExist();
        assertThat(remote.resolve("archive/cad.csv")).exists();
        assertThat(pull(connector)).isEmpty();
    }

    /**
     * A nightly export named the same thing every night is the common case, not an edge one.
     *
     * <p>Overwriting would destroy last night's archived copy; failing would stop the run dead on
     * the second night. Both are worse than a suffixed name.
     */
    @Test
    @DisplayName("archiving onto a name that is taken keeps both copies")
    void archiveDoesNotOverwrite() throws IOException {
        Files.createDirectories(remote.resolve("archive"));
        Files.writeString(remote.resolve("archive/cad.csv"), "last night\n");
        server.give("cad.csv", "tonight\n", MONDAY);
        SftpConnector connector = connectorWith(Map.of(
                "afterDownload", "archive",
                "archiveDirectory", "/archive"));

        assertThat(pull(connector)).hasSize(1);

        assertThat(Files.readString(remote.resolve("archive/cad.csv"))).isEqualTo("last night\n");
        try (Stream<Path> archived = Files.list(remote.resolve("archive"))) {
            assertThat(archived).hasSize(2);
        }
    }

    @Test
    @DisplayName("deleting removes a landed file from the remote server")
    void deleteRemoves() throws IOException {
        server.give("cad.csv", "1\n", MONDAY);
        SftpConnector connector = connectorWith(Map.of("afterDownload", "delete"));

        assertThat(pull(connector)).hasSize(1);

        assertThat(remote.resolve("cad.csv")).doesNotExist();
    }

    /**
     * The property the whole handle is built around, and the one ADR 0028 found in Kafka.
     *
     * <p>A bronze batch boundary falls wherever it falls, usually mid-file. Archiving a file whose
     * remaining lines are still unread would discard records bronze never received, and no later run
     * would look for them: the file is gone.
     */
    @Test
    @DisplayName("a file still being read is never archived, however the batches fall")
    void aPartlyReadFileIsNotAcknowledged() throws IOException {
        Files.createDirectories(remote.resolve("archive"));
        server.give("big.csv", "1\n2\n3\n4\n", MONDAY);
        SftpConnector connector = connectorWith(Map.of(
                "afterDownload", "archive",
                "archiveDirectory", "/archive"));

        // Acknowledge after two of the four records, exactly as a small batch size would.
        try (SourceHandle handle = connector.open(); Stream<RawEnvelope> envelopes = handle.envelopes()) {
            var iterator = envelopes.iterator();
            iterator.next();
            iterator.next();
            handle.acknowledge();

            assertThat(remote.resolve("big.csv")).exists();
            assertThat(remote.resolve("archive/big.csv")).doesNotExist();

            iterator.next();
            iterator.next();
            handle.acknowledge();
        }

        assertThat(remote.resolve("big.csv")).doesNotExist();
        assertThat(remote.resolve("archive/big.csv")).exists();
    }

    @Test
    @DisplayName("a run that dies mid-file leaves the file to be re-read, not skipped")
    void anAbandonedPullLeavesTheFile() throws IOException {
        server.give("cad.csv", "1\n2\n3\n4\n", MONDAY);
        SftpConnector connector = connectorWith(Map.of("afterDownload", "watermark"));

        try (SourceHandle handle = connector.open(); Stream<RawEnvelope> envelopes = handle.envelopes()) {
            var iterator = envelopes.iterator();
            iterator.next();
            // Closed without acknowledging: the process died here.
        }

        assertThat(checkpoints.read("riverton-pd-cad", "cad-sftp-1")).isEmpty();
        assertThat(pull(connector)).hasSize(4);
    }

    @Test
    @DisplayName("maxFiles caps a pull in read order, so the rest is next run's work")
    void capsAPull() throws IOException {
        server.give("a.csv", "a\n", MONDAY);
        server.give("b.csv", "b\n", TUESDAY);
        server.give("c.csv", "c\n", WEDNESDAY);
        SftpConnector connector = connectorWith(Map.of(
                "afterDownload", "watermark",
                "maxFiles", "2"));

        assertThat(pull(connector)).extracting(envelope -> envelope.offset().value())
                .containsExactly("a.csv#000001", "b.csv#000001");
        assertThat(pull(connector)).extracting(envelope -> envelope.offset().value())
                .containsExactly("c.csv#000001");
    }

    @Test
    @DisplayName("an empty directory is a healthy idle source, not a failure")
    void emptyIsFine() throws IOException {
        SftpConnector connector = connectorWith(Map.of("afterDownload", "none"));

        assertThat(pull(connector)).isEmpty();
        assertThat(connector.health().toString()).contains("HEALTHY");
    }

    @Test
    @DisplayName("health reports a missing remote directory rather than claiming to be well")
    void healthSeesAMissingDirectory() throws IOException {
        SftpConnector connector = connectorWith(Map.of(
                "afterDownload", "none",
                "directory", "/not-there"));

        assertThat(connector.health().toString()).contains("UNAVAILABLE");
    }

    /**
     * An archive directory that is not there only fails at acknowledgement time otherwise -- which
     * is after the records are already in bronze, and after the run has reported success.
     */
    @Test
    @DisplayName("health reports a missing archive directory before a run needs it")
    void healthSeesAMissingArchiveDirectory() throws IOException {
        SftpConnector connector = connectorWith(Map.of(
                "afterDownload", "archive",
                "archiveDirectory", "/archive"));

        assertThat(connector.health().toString()).contains("DEGRADED");
    }

    /** Drains a pull the way {@code LandingService} does: read to the end, then acknowledge. */
    private List<RawEnvelope> pull(SftpConnector connector) {
        List<RawEnvelope> landed = new ArrayList<>();
        try (SourceHandle handle = connector.open(); Stream<RawEnvelope> envelopes = handle.envelopes()) {
            envelopes.forEach(landed::add);
            handle.acknowledge();
        }
        return landed;
    }

    private SftpConnector connectorWith(Map<String, String> extra) {
        Map<String, String> settings = new HashMap<>(Map.of(
                "host", "127.0.0.1",
                "port", Integer.toString(server.port()),
                "username", EmbeddedSftpServer.USERNAME,
                "password", EmbeddedSftpServer.PASSWORD,
                "hostKeyCheck", "accept-any",
                "directory", "/",
                "retention", "retained"));
        settings.putAll(extra);

        SftpConnector connector = new SftpConnector(Clock.fixed(NOW, ZoneOffset.UTC));
        connector.useCheckpointStore(checkpoints);
        connector.configure(ConnectorConfig.of(
                "riverton-pd-cad", "cad-sftp-1", SftpConnector.TYPE, settings));
        return connector;
    }
}
