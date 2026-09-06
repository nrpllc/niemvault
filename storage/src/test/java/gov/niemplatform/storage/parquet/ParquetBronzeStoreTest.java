package gov.niemplatform.storage.parquet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.storage.api.BronzeBatch;
import gov.niemplatform.storage.api.BronzeBatchReceipt;
import gov.niemplatform.storage.api.BronzeRange;
import gov.niemplatform.storage.api.BronzeStorageException;
import gov.niemplatform.storage.api.RawEnvelope;
import gov.niemplatform.storage.api.SourceOffset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bronze landing (spec §4.4), including the properties everything downstream depends on.
 *
 * <p>Acceptance criterion 1 is asserted here: a sample source record lands byte-preserved with a
 * complete envelope. So is the property criterion 6 rests on -- that what comes back out is
 * exactly what went in, every time.
 */
class ParquetBronzeStoreTest {

    private static final String SOURCE = "riverton-pd-cad";
    private static final String CONNECTOR = "file-drop-01";
    private static final Instant INGEST = Instant.parse("2026-03-04T17:25:00Z");

    @TempDir
    Path root;

    private ParquetBronzeStore store;

    @BeforeEach
    void setUp() {
        store = new ParquetBronzeStore(root, Clock.fixed(INGEST, ZoneOffset.UTC));
    }

    private static RawEnvelope envelope(String payload, String offset, Instant asserted) {
        return new RawEnvelope(SOURCE, CONNECTOR, INGEST, asserted,
                payload.getBytes(StandardCharsets.UTF_8), SourceOffset.of(offset));
    }

    private static BronzeBatch batchOf(RawEnvelope... envelopes) {
        return new BronzeBatch(SOURCE, CONNECTOR, List.of(envelopes));
    }

    private static final String CAD_ROW = "2026-000114,BURG,2026/03/04 11:20,\"418 W 9TH ST\",3A";

    @Test
    @DisplayName("a landed record keeps its bytes exactly, with a complete envelope")
    void landsRecordBytePreserved() {
        RawEnvelope landed = envelope(CAD_ROW, "incidents.csv#000001",
                Instant.parse("2026-03-04T17:20:00Z"));

        BronzeBatchReceipt receipt = store.append(batchOf(landed));

        assertThat(receipt.recordCount()).isEqualTo(1);
        try (Stream<RawEnvelope> read = store.read(SOURCE, BronzeRange.all())) {
            assertThat(read.toList()).singleElement().satisfies(envelope -> {
                assertThat(envelope.payloadAsText()).isEqualTo(CAD_ROW);
                assertThat(envelope.sourceId()).isEqualTo(SOURCE);
                assertThat(envelope.connectorInstanceId()).isEqualTo(CONNECTOR);
                assertThat(envelope.ingestTimestamp()).isEqualTo(INGEST);
                assertThat(envelope.sourceAssertedTimestamp())
                        .contains(Instant.parse("2026-03-04T17:20:00Z"));
                assertThat(envelope.offset()).isEqualTo(SourceOffset.of("incidents.csv#000001"));
                assertThat(envelope.contentHash().algorithm()).isEqualTo("sha256");
            });
        }
    }

    @Test
    @DisplayName("bytes survive a round trip even when they are not valid text")
    void binaryPayloadSurvives() {
        byte[] awkward = {0x00, (byte) 0xFF, 0x0D, 0x0A, (byte) 0xC3, (byte) 0x28, 0x1A};
        RawEnvelope landed = new RawEnvelope(SOURCE, CONNECTOR, INGEST, null, awkward,
                SourceOffset.of("raw#000001"));

        store.append(batchOf(landed));

        try (Stream<RawEnvelope> read = store.read(SOURCE, BronzeRange.all())) {
            assertThat(read.toList().getFirst().payload()).containsExactly(awkward);
        }
    }

    @Test
    @DisplayName("a source asserting no timestamp is distinguishable from one asserting a time")
    void absentSourceTimestamp() {
        store.append(batchOf(envelope(CAD_ROW, "incidents.csv#000001", null)));

        try (Stream<RawEnvelope> read = store.read(SOURCE, BronzeRange.all())) {
            assertThat(read.toList().getFirst().sourceAssertedTimestamp()).isEmpty();
        }
    }

    @Test
    @DisplayName("envelope identity is derived, so replay reproduces it")
    void identityIsDeterministic() {
        RawEnvelope first = envelope(CAD_ROW, "incidents.csv#000001", null);
        RawEnvelope again = envelope(CAD_ROW, "incidents.csv#000001", null);

        assertThat(first.envelopeId()).isEqualTo(again.envelopeId());
        assertThat(envelope(CAD_ROW, "incidents.csv#000002", null).envelopeId())
                .isNotEqualTo(first.envelopeId());
    }

    @Nested
    @DisplayName("batches")
    class Batches {

        @Test
        @DisplayName("batch identifiers sort by landing order")
        void batchIdsSort() {
            store.append(batchOf(envelope("a", "f#000001", null)));
            store.append(batchOf(envelope("b", "f#000002", null)));
            store.append(batchOf(envelope("c", "f#000003", null)));

            assertThat(store.batches(SOURCE)).extracting(BronzeBatchReceipt::batchId)
                    .containsExactly("b-000000000001", "b-000000000002", "b-000000000003")
                    .isSorted();
        }

        @Test
        @DisplayName("numbering continues after a restart rather than colliding")
        void numberingSurvivesRestart() {
            store.append(batchOf(envelope("a", "f#000001", null)));

            ParquetBronzeStore restarted = new ParquetBronzeStore(root, Clock.fixed(INGEST, ZoneOffset.UTC));
            restarted.append(batchOf(envelope("b", "f#000002", null)));

            assertThat(restarted.batches(SOURCE)).extracting(BronzeBatchReceipt::batchId)
                    .containsExactly("b-000000000001", "b-000000000002");
        }

        @Test
        @DisplayName("a range selects only the batches asked for, which is what replay needs")
        void rangeSelectsBatches() {
            store.append(batchOf(envelope("a", "f#000001", null)));
            store.append(batchOf(envelope("b", "f#000002", null)));
            store.append(batchOf(envelope("c", "f#000003", null)));

            try (Stream<RawEnvelope> read =
                    store.read(SOURCE, BronzeRange.between("b-000000000002", "b-000000000002"))) {
                assertThat(read.toList()).extracting(RawEnvelope::payloadAsText).containsExactly("b");
            }
        }

        @Test
        @DisplayName("records come back in batch order and then source order")
        void readOrderIsStable() {
            store.append(batchOf(
                    envelope("one", "f#000001", null),
                    envelope("two", "f#000002", null)));
            store.append(batchOf(envelope("three", "f#000003", null)));

            try (Stream<RawEnvelope> read = store.read(SOURCE, BronzeRange.all())) {
                assertThat(read.toList()).extracting(RawEnvelope::payloadAsText)
                        .containsExactly("one", "two", "three");
            }
        }

        @Test
        @DisplayName("a batch cannot mix sources")
        void batchCannotMixSources() {
            RawEnvelope foreign = new RawEnvelope("other-source", CONNECTOR, INGEST, null,
                    "x".getBytes(StandardCharsets.UTF_8), SourceOffset.of("f#000001"));

            assertThatThrownBy(() -> new BronzeBatch(SOURCE, CONNECTOR, List.of(foreign)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("other-source");
        }
    }

    @Nested
    @DisplayName("the manifest is the commit")
    class Manifest {

        @Test
        @DisplayName("a batch with no manifest is invisible, not half-read")
        void uncommittedBatchIsInvisible() throws Exception {
            store.append(batchOf(envelope("committed", "f#000001", null)));
            store.append(batchOf(envelope("interrupted", "f#000002", null)));

            // Simulate a crash between writing the data file and committing the manifest.
            Files.delete(root.resolve(SOURCE).resolve("b-000000000002").resolve("manifest.json"));

            assertThat(store.batches(SOURCE)).hasSize(1);
            try (Stream<RawEnvelope> read = store.read(SOURCE, BronzeRange.all())) {
                assertThat(read.toList()).extracting(RawEnvelope::payloadAsText)
                        .containsExactly("committed");
            }
        }

        @Test
        @DisplayName("the manifest describes the batch without a Parquet reader")
        void manifestIsSelfDescribing() throws Exception {
            store.append(batchOf(
                    envelope(CAD_ROW, "incidents.csv#000001", Instant.parse("2026-03-04T17:20:00Z")),
                    envelope("second", "incidents.csv#000002", null)));

            String manifest = Files.readString(
                    root.resolve(SOURCE).resolve("b-000000000001").resolve("manifest.json"));

            assertThat(manifest)
                    .contains("\"recordCount\" : 2")
                    .contains("riverton-pd-cad")
                    .contains("incidents.csv#000001")
                    .contains("sha256:");
        }

        @Test
        @DisplayName("the manifest holds metadata, never payloads")
        void manifestHoldsNoPayloads() throws Exception {
            store.append(batchOf(envelope(CAD_ROW, "incidents.csv#000001", null)));

            String manifest = Files.readString(
                    root.resolve(SOURCE).resolve("b-000000000001").resolve("manifest.json"));

            assertThat(manifest).doesNotContain("418 W 9TH ST").doesNotContain("BURG");
        }
    }

    @Test
    @DisplayName("a tampered payload is caught on read rather than reaching silver")
    void tamperingIsCaught() throws Exception {
        store.append(batchOf(envelope(CAD_ROW, "incidents.csv#000001", null)));

        // Rewrite the manifest's recorded count to disagree with the data file.
        Path manifest = root.resolve(SOURCE).resolve("b-000000000001").resolve("manifest.json");
        Files.writeString(manifest,
                Files.readString(manifest).replace("\"recordCount\" : 1", "\"recordCount\" : 2"));

        assertThatThrownBy(() -> {
            try (Stream<RawEnvelope> read = store.read(SOURCE, BronzeRange.all())) {
                read.toList();
            }
        })
                .isInstanceOf(BronzeStorageException.class)
                .satisfies(thrown -> assertThat(((BronzeStorageException) thrown).operation())
                        .isEqualTo(BronzeStorageException.Operation.INTEGRITY));
    }

    @Test
    @DisplayName("an envelope never prints its payload")
    void envelopeRedactsPayload() {
        RawEnvelope landed = envelope(CAD_ROW, "incidents.csv#000001", null);

        assertThat(landed.toString())
                .doesNotContain("418 W 9TH ST", "BURG")
                .contains("riverton-pd-cad", "incidents.csv#000001");
    }

    @Test
    @DisplayName("a payload handed out cannot be used to alter what is stored")
    void payloadIsDefensivelyCopied() {
        RawEnvelope landed = envelope(CAD_ROW, "incidents.csv#000001", null);

        byte[] handedOut = landed.payload();
        handedOut[0] = 'X';

        assertThat(landed.payloadAsText()).isEqualTo(CAD_ROW);
    }

    @Test
    @DisplayName("sources are discoverable, and an unlanded source reads as empty")
    void sourceDiscovery() {
        store.append(batchOf(envelope("a", "f#000001", null)));

        assertThat(store.sources()).containsExactly(SOURCE);
        assertThat(store.batches("never-landed")).isEmpty();
    }
}
