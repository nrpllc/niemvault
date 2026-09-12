package gov.niemplatform.connectors.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A read position outlives the run that wrote it (ADR 0030).
 *
 * <p>The whole reason the file-backed store exists. A Kafka consumer group is remembered by the
 * broker, so a restart resumes for free; an SFTP directory and a change log remember nothing, and a
 * position kept in memory means every restart re-reads the source from its beginning. For a nightly
 * pull that is a second full copy in bronze every night, arrived at silently, with every log line
 * saying the run succeeded.
 */
class APositionSurvivesTheProcessTest {

    private static final Instant WHEN = Instant.parse("2026-03-04T11:20:00Z");

    @Test
    @DisplayName("a position written by one store is read back by the next one opened")
    void survivesReopening(@TempDir Path directory) {
        FileSourceCheckpointStore first = FileSourceCheckpointStore.under(directory);
        first.write(SourceCheckpoint.of("riverton-pd-cad", "cad-sftp-1", "cad-2026-03-04.csv", WHEN));

        // A different store object over the same directory is what a restarted process gets.
        FileSourceCheckpointStore reopened = FileSourceCheckpointStore.under(directory);

        assertThat(reopened.read("riverton-pd-cad", "cad-sftp-1"))
                .map(SourceCheckpoint::position)
                .contains("cad-2026-03-04.csv");
    }

    @Test
    @DisplayName("a source never read has no position, which is not an error")
    void neverReadIsEmpty(@TempDir Path directory) {
        FileSourceCheckpointStore store = FileSourceCheckpointStore.under(directory);

        assertThat(store.read("riverton-pd-cad", "cad-sftp-1")).isEmpty();
    }

    @Test
    @DisplayName("a later position replaces an earlier one rather than accumulating")
    void writesReplace(@TempDir Path directory) {
        FileSourceCheckpointStore store = FileSourceCheckpointStore.under(directory);
        store.write(SourceCheckpoint.of("riverton-pd-cad", "cad-sftp-1", "cad-2026-03-03.csv", WHEN));
        store.write(SourceCheckpoint.of(
                "riverton-pd-cad", "cad-sftp-1", "cad-2026-03-04.csv", WHEN.plusSeconds(86_400)));

        assertThat(store.read("riverton-pd-cad", "cad-sftp-1"))
                .map(SourceCheckpoint::position)
                .contains("cad-2026-03-04.csv");
        assertThat(store.readAll()).hasSize(1);
    }

    @Test
    @DisplayName("two instances of one source keep separate positions")
    void instancesAreIndependent(@TempDir Path directory) {
        FileSourceCheckpointStore store = FileSourceCheckpointStore.under(directory);
        store.write(SourceCheckpoint.of("riverton-pd-cad", "cad-sftp-1", "a.csv", WHEN));
        store.write(SourceCheckpoint.of("riverton-pd-cad", "cad-sftp-2", "b.csv", WHEN));

        assertThat(store.read("riverton-pd-cad", "cad-sftp-1"))
                .map(SourceCheckpoint::position).contains("a.csv");
        assertThat(store.read("riverton-pd-cad", "cad-sftp-2"))
                .map(SourceCheckpoint::position).contains("b.csv");
    }

    /**
     * Two ids that differ only in characters a filename cannot hold must still get two files.
     *
     * <p>Sanitising alone maps both to one name, and the second connector then reads the first
     * one's position -- skipping everything the other instance had already pulled, from a source it
     * has never read.
     */
    @Test
    @DisplayName("ids that sanitise to the same filename do not share a position")
    void sanitisingDoesNotCollide(@TempDir Path directory) {
        FileSourceCheckpointStore store = FileSourceCheckpointStore.under(directory);
        store.write(SourceCheckpoint.of("riverton/cad", "instance-1", "slash.csv", WHEN));
        store.write(SourceCheckpoint.of("riverton:cad", "instance-1", "colon.csv", WHEN));

        assertThat(store.read("riverton/cad", "instance-1"))
                .map(SourceCheckpoint::position).contains("slash.csv");
        assertThat(store.read("riverton:cad", "instance-1"))
                .map(SourceCheckpoint::position).contains("colon.csv");
        assertThat(store.readAll()).hasSize(2);
    }

    @Test
    @DisplayName("clearing a position makes the next run start from the beginning")
    void clearingForgets(@TempDir Path directory) {
        FileSourceCheckpointStore store = FileSourceCheckpointStore.under(directory);
        store.write(SourceCheckpoint.of("riverton-pd-cad", "cad-sftp-1", "cad-2026-03-04.csv", WHEN));

        store.clear("riverton-pd-cad", "cad-sftp-1");

        assertThat(store.read("riverton-pd-cad", "cad-sftp-1")).isEmpty();
    }

    @Test
    @DisplayName("clearing a source that was never read is not an error")
    void clearingNothingIsFine(@TempDir Path directory) {
        FileSourceCheckpointStore store = FileSourceCheckpointStore.under(directory);

        store.clear("riverton-pd-cad", "cad-sftp-1");

        assertThat(store.readAll()).isEmpty();
    }

    /**
     * A damaged position file is an error, not a silent restart from zero.
     *
     * <p>Restarting from the beginning is safe -- it duplicates, and bronze detects duplicates --
     * but it is a decision that belongs to an operator, and one arrived at by swallowing a parse
     * failure is one nobody knows was made.
     */
    @Test
    @DisplayName("a corrupt position file is refused rather than treated as never-read")
    void corruptIsRefused(@TempDir Path directory) throws IOException {
        FileSourceCheckpointStore store = FileSourceCheckpointStore.under(directory);
        store.write(SourceCheckpoint.of("riverton-pd-cad", "cad-sftp-1", "cad.csv", WHEN));
        Path file = store.fileFor("riverton-pd-cad", "cad-sftp-1");
        Files.writeString(file, "position: [not, a, scalar]\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> store.read("riverton-pd-cad", "cad-sftp-1"))
                .isInstanceOf(FileSourceCheckpointStore.CheckpointStoreException.class)
                .hasMessageContaining("sourceId");
    }

    @Test
    @DisplayName("a truncated position file is refused rather than read as a partial position")
    void truncatedIsRefused(@TempDir Path directory) throws IOException {
        FileSourceCheckpointStore store = FileSourceCheckpointStore.under(directory);
        store.write(SourceCheckpoint.of("riverton-pd-cad", "cad-sftp-1", "cad.csv", WHEN));
        Files.writeString(store.fileFor("riverton-pd-cad", "cad-sftp-1"), "", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> store.read("riverton-pd-cad", "cad-sftp-1"))
                .isInstanceOf(FileSourceCheckpointStore.CheckpointStoreException.class);
    }

    @Test
    @DisplayName("a blank position is refused, because absent already means never-read")
    void blankPositionIsRefused() {
        assertThatThrownBy(() -> SourceCheckpoint.of("riverton-pd-cad", "cad-sftp-1", "  ", WHEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never read");
    }

    @Test
    @DisplayName("positions list in a stable order whatever order they were written in")
    void listingIsStable(@TempDir Path directory) {
        FileSourceCheckpointStore store = FileSourceCheckpointStore.under(directory);
        store.write(SourceCheckpoint.of("zeta-source", "instance-1", "z.csv", WHEN));
        store.write(SourceCheckpoint.of("alpha-source", "instance-2", "a2.csv", WHEN));
        store.write(SourceCheckpoint.of("alpha-source", "instance-1", "a1.csv", WHEN));

        assertThat(store.readAll())
                .extracting(SourceCheckpoint::sourceId, SourceCheckpoint::connectorInstanceId)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("alpha-source", "instance-1"),
                        org.assertj.core.api.Assertions.tuple("alpha-source", "instance-2"),
                        org.assertj.core.api.Assertions.tuple("zeta-source", "instance-1"));
    }

    /**
     * A deployment that configured no store must fail loudly, at configuration time.
     *
     * <p>The alternative is a connector that runs perfectly and re-lands its entire source on every
     * invocation. Nothing in the run reports a problem; bronze simply grows by a full copy a night.
     */
    @Test
    @DisplayName("a connector needing a position and given none is refused, naming what to set")
    void unavailableRefusesUsably() {
        SourceCheckpointStore none = SourceCheckpointStore.unavailable();

        assertThatThrownBy(() -> none.requireUsable("riverton-pd-cad", "cad-sftp-1"))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("checkpointStore")
                .hasMessageContaining("--checkpoints");
        assertThatThrownBy(() -> none.write(
                SourceCheckpoint.of("riverton-pd-cad", "cad-sftp-1", "cad.csv", WHEN)))
                .isInstanceOf(ConnectorConfigurationException.class);
    }

    @Test
    @DisplayName("the in-memory store is usable, so a test connector is not refused")
    void inMemoryIsUsable() {
        SourceCheckpointStore store = SourceCheckpointStore.inMemory();

        store.requireUsable("riverton-pd-cad", "cad-sftp-1");
        store.write(SourceCheckpoint.of("riverton-pd-cad", "cad-sftp-1", "cad.csv", WHEN));

        assertThat(store.read("riverton-pd-cad", "cad-sftp-1"))
                .map(SourceCheckpoint::position).contains("cad.csv");
        assertThat(store.readAll()).hasSize(1);
        store.clear("riverton-pd-cad", "cad-sftp-1");
        assertThat(store.read("riverton-pd-cad", "cad-sftp-1")).isEmpty();
    }

    /**
     * Every connector is offered a store, including the ones that will ignore it.
     *
     * <p>Offering it only to transports believed to need one would put the platform in the business
     * of knowing which those are, and the next connector shipped as a jar is the one it would not
     * know about.
     */
    @Test
    @DisplayName("resolving a connector offers it a store before configuring it")
    void theStoreArrivesBeforeConfiguration() {
        List<String> journal = new java.util.ArrayList<>();
        SourceConnector connector = new RecordingConnector(journal);
        SourceDefinition definition = new SourceDefinition(
                "riverton-pd-cad", "cad-1", ConnectorType.of("recording"), java.util.Map.of(), null);

        definition.connectorFrom(ConnectorRegistry.of(connector), SourceCheckpointStore.inMemory());

        assertThat(journal).containsExactly("checkpoint-store", "configure");
    }

    @Test
    @DisplayName("resolving without a store hands over one that refuses, not one that forgets")
    void theDefaultStoreRefuses() {
        List<SourceCheckpointStore> offered = new java.util.ArrayList<>();
        SourceConnector connector = new RecordingConnector(new java.util.ArrayList<>()) {
            @Override
            public void useCheckpointStore(SourceCheckpointStore store) {
                offered.add(store);
            }
        };
        SourceDefinition definition = new SourceDefinition(
                "riverton-pd-cad", "cad-1", ConnectorType.of("recording"), java.util.Map.of(), null);

        definition.connectorFrom(ConnectorRegistry.of(connector));

        assertThat(offered).hasSize(1);
        assertThatThrownBy(() -> offered.getFirst().requireUsable("riverton-pd-cad", "cad-1"))
                .isInstanceOf(ConnectorConfigurationException.class);
    }

    /** A connector that records the order it was called in, and reads nothing. */
    private static class RecordingConnector implements SourceConnector {

        private final List<String> journal;

        RecordingConnector(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public ConnectorType type() {
            return ConnectorType.of("recording");
        }

        @Override
        public InteractionMode interactionMode() {
            return InteractionMode.POLL;
        }

        @Override
        public RetentionPosture retention() {
            return RetentionPosture.RETAINED;
        }

        @Override
        public void useCheckpointStore(SourceCheckpointStore store) {
            journal.add("checkpoint-store");
        }

        @Override
        public void configure(ConnectorConfig config) {
            journal.add("configure");
        }

        @Override
        public SourceHandle open() {
            throw new UnsupportedOperationException("not opened in this test");
        }

        @Override
        public HealthStatus health() {
            return HealthStatus.healthy(WHEN);
        }

        @Override
        public void close() {
            // Nothing held.
        }
    }
}
