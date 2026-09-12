package gov.niemplatform.connectors.ftp;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.connectors.api.ConnectorConfig;
import gov.niemplatform.connectors.api.SourceCheckpointStore;
import gov.niemplatform.connectors.api.SourceHandle;
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
import org.apache.ftpserver.ftplet.FtpException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pull over FTP behaves exactly as a pull over SFTP (ADR 0031, ADR 0033).
 *
 * <p>That is the claim the {@code connectors:pull} extraction makes, and it is worth testing rather
 * than assuming. The postures, the read order, the rule that only a fully drained file is
 * acknowledged -- none of them may differ by protocol, because a source moving between transports
 * must not change what lands in bronze.
 *
 * <p>These run against a real FTP server. FTP's failures are on the server side, and they are the
 * ones a double cannot produce.
 */
class AnFtpPullBehavesLikeEveryOtherPullTest {

    private static final Instant NOW = Instant.parse("2026-03-04T11:20:00Z");
    private static final Instant MONDAY = Instant.parse("2026-03-02T06:00:00Z");
    private static final Instant TUESDAY = Instant.parse("2026-03-03T06:00:00Z");
    private static final Instant WEDNESDAY = Instant.parse("2026-03-04T06:00:00Z");

    @TempDir
    Path remote;

    private EmbeddedFtpServer server;
    private SourceCheckpointStore checkpoints;

    @BeforeEach
    void startServer() throws IOException, FtpException {
        server = EmbeddedFtpServer.serving(remote);
        checkpoints = SourceCheckpointStore.inMemory();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    @DisplayName("a line-mode pull lands one record per line, stamped with the file's own time")
    void landsLines() throws IOException {
        server.give("cad-2026-03-02.csv", "id,kind\n1,theft\n2,assault\n", MONDAY);
        FtpsConnector connector = connectorWith(Map.of(
                "afterDownload", "none",
                "skipHeaderLines", "1"));

        List<RawEnvelope> landed = pull(connector);

        assertThat(landed).hasSize(2);
        assertThat(new String(landed.getFirst().payload(), StandardCharsets.UTF_8)).isEqualTo("1,theft");
        // The same offset shape the file drop and the SFTP pull produce. A source that moves
        // between transports keeps comparable offsets, so a bronze range spanning the move still
        // means one thing.
        assertThat(landed.getFirst().offset().value()).isEqualTo("cad-2026-03-02.csv#000002");
    }

    /**
     * The failure that makes ASCII mode dangerous rather than merely quaint.
     *
     * <p>FTP defaults to ASCII, which rewrites line endings in flight. A file pulled that way
     * arrives with bytes the source never sent, and since envelope identity is derived from a
     * content hash, the same file would land with two identities depending on transfer mode --
     * which defeats the duplicate detection every re-read posture relies on.
     */
    @Test
    @DisplayName("bytes arrive exactly as the source wrote them, CRLF included")
    void transfersAreByteExact() throws IOException {
        server.give("windows.csv", "a,b\r\nc,d\r\n", MONDAY);
        FtpsConnector connector = connectorWith(Map.of(
                "afterDownload", "none",
                "recordMode", "file"));

        List<RawEnvelope> landed = pull(connector);

        assertThat(landed).hasSize(1);
        assertThat(new String(landed.getFirst().payload(), StandardCharsets.UTF_8))
                .isEqualTo("a,b\r\nc,d\r\n");
    }

    @Test
    @DisplayName("files are read oldest first, so the order they land in is the order they arrived")
    void readsInTimeOrder() throws IOException {
        server.give("zebra.csv", "z\n", MONDAY);
        server.give("alpha.csv", "a\n", WEDNESDAY);
        FtpsConnector connector = connectorWith(Map.of("afterDownload", "none"));

        assertThat(pull(connector)).extracting(envelope -> envelope.offset().value())
                .containsExactly("zebra.csv#000001", "alpha.csv#000001");
    }

    @Test
    @DisplayName("only files matching the pattern are pulled")
    void honoursThePattern() throws IOException {
        server.give("cad.csv", "1\n", MONDAY);
        server.give("notes.txt", "ignore me\n", MONDAY);
        FtpsConnector connector = connectorWith(Map.of(
                "afterDownload", "none",
                "filePattern", "*.csv"));

        assertThat(pull(connector)).hasSize(1);
    }

    @Test
    @DisplayName("a subdirectory in the drop directory is skipped rather than failing the pull")
    void skipsDirectories() throws IOException {
        server.give("cad.csv", "1\n", MONDAY);
        Files.createDirectories(remote.resolve("archive"));
        FtpsConnector connector = connectorWith(Map.of("afterDownload", "none"));

        assertThat(pull(connector)).hasSize(1);
    }

    @Test
    @DisplayName("with afterDownload none, a second run re-reads everything")
    void noneRereads() throws IOException {
        server.give("cad.csv", "1\n2\n", MONDAY);
        FtpsConnector connector = connectorWith(Map.of("afterDownload", "none"));

        assertThat(pull(connector)).hasSize(2);
        assertThat(pull(connector)).hasSize(2);
    }

    @Test
    @DisplayName("with a watermark, a second run reads only what arrived since")
    void watermarkAdvances() throws IOException {
        server.give("cad-mon.csv", "mon\n", MONDAY);
        FtpsConnector connector = connectorWith(Map.of("afterDownload", "watermark"));

        assertThat(pull(connector)).hasSize(1);
        assertThat(pull(connector)).isEmpty();

        server.give("cad-tue.csv", "tue\n", TUESDAY);

        assertThat(pull(connector)).extracting(
                        envelope -> new String(envelope.payload(), StandardCharsets.UTF_8))
                .containsExactly("tue");
    }

    @Test
    @DisplayName("archiving moves a landed file, so the next run does not see it")
    void archiveMoves() throws IOException {
        Files.createDirectories(remote.resolve("archive"));
        server.give("cad.csv", "1\n", MONDAY);
        FtpsConnector connector = connectorWith(Map.of(
                "afterDownload", "archive",
                "archiveDirectory", "/archive"));

        assertThat(pull(connector)).hasSize(1);

        assertThat(remote.resolve("cad.csv")).doesNotExist();
        assertThat(remote.resolve("archive/cad.csv")).exists();
        assertThat(pull(connector)).isEmpty();
    }

    @Test
    @DisplayName("archiving onto a name that is taken keeps both copies")
    void archiveDoesNotOverwrite() throws IOException {
        Files.createDirectories(remote.resolve("archive"));
        Files.writeString(remote.resolve("archive/cad.csv"), "last night\n");
        server.give("cad.csv", "tonight\n", MONDAY);
        FtpsConnector connector = connectorWith(Map.of(
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
        FtpsConnector connector = connectorWith(Map.of("afterDownload", "delete"));

        assertThat(pull(connector)).hasSize(1);

        assertThat(remote.resolve("cad.csv")).doesNotExist();
    }

    /** The property the shared handle exists to guarantee, verified over a second protocol. */
    @Test
    @DisplayName("a file still being read is never archived, however the batches fall")
    void aPartlyReadFileIsNotAcknowledged() throws IOException {
        Files.createDirectories(remote.resolve("archive"));
        server.give("big.csv", "1\n2\n3\n4\n", MONDAY);
        FtpsConnector connector = connectorWith(Map.of(
                "afterDownload", "archive",
                "archiveDirectory", "/archive"));

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

        assertThat(remote.resolve("archive/big.csv")).exists();
    }

    @Test
    @DisplayName("a run that dies mid-file leaves the file to be re-read, not skipped")
    void anAbandonedPullLeavesTheFile() throws IOException {
        server.give("cad.csv", "1\n2\n3\n4\n", MONDAY);
        FtpsConnector connector = connectorWith(Map.of("afterDownload", "watermark"));

        try (SourceHandle handle = connector.open(); Stream<RawEnvelope> envelopes = handle.envelopes()) {
            envelopes.iterator().next();
        }

        assertThat(checkpoints.read("riverton-pd-cad", "cad-ftps-1")).isEmpty();
        assertThat(pull(connector)).hasSize(4);
    }

    @Test
    @DisplayName("maxFiles caps a pull in read order, so the rest is next run's work")
    void capsAPull() throws IOException {
        server.give("a.csv", "a\n", MONDAY);
        server.give("b.csv", "b\n", TUESDAY);
        server.give("c.csv", "c\n", WEDNESDAY);
        FtpsConnector connector = connectorWith(Map.of(
                "afterDownload", "watermark",
                "maxFiles", "2"));

        assertThat(pull(connector)).extracting(envelope -> envelope.offset().value())
                .containsExactly("a.csv#000001", "b.csv#000001");
        assertThat(pull(connector)).extracting(envelope -> envelope.offset().value())
                .containsExactly("c.csv#000001");
    }

    @Test
    @DisplayName("an empty directory is a healthy idle source, not a failure")
    void emptyIsFine() {
        FtpsConnector connector = connectorWith(Map.of("afterDownload", "none"));

        assertThat(pull(connector)).isEmpty();
        assertThat(connector.health().toString()).contains("HEALTHY");
    }

    @Test
    @DisplayName("health reports a missing remote directory rather than claiming to be well")
    void healthSeesAMissingDirectory() {
        FtpsConnector connector = connectorWith(Map.of(
                "afterDownload", "none",
                "directory", "/not-there"));

        assertThat(connector.health().toString()).contains("UNAVAILABLE");
    }

    /** Drains a pull the way {@code LandingService} does: read to the end, then acknowledge. */
    private List<RawEnvelope> pull(FtpsConnector connector) {
        List<RawEnvelope> landed = new ArrayList<>();
        try (SourceHandle handle = connector.open(); Stream<RawEnvelope> envelopes = handle.envelopes()) {
            envelopes.forEach(landed::add);
            handle.acknowledge();
        }
        return landed;
    }

    private FtpsConnector connectorWith(Map<String, String> extra) {
        Map<String, String> settings = new HashMap<>(Map.of(
                "host", "127.0.0.1",
                "port", Integer.toString(server.port()),
                "username", EmbeddedFtpServer.USERNAME,
                "password", EmbeddedFtpServer.PASSWORD,
                "security", "none",
                "directory", "/",
                "retention", "retained"));
        settings.putAll(extra);

        FtpsConnector connector = new FtpsConnector(Clock.fixed(NOW, ZoneOffset.UTC));
        connector.useCheckpointStore(checkpoints);
        connector.configure(ConnectorConfig.of(
                "riverton-pd-cad", "cad-ftps-1", FtpsConnector.TYPE, settings));
        return connector;
    }
}
