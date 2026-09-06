package gov.niemplatform.storage.parquet;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Decides ADR 0005 for bronze: can Parquet be written and read on a developer machine without a
 * Hadoop installation?
 *
 * <p>Spec §2 pins bronze to Parquet, and §6 makes air-gapped, self-contained delivery mandatory.
 * Parquet's builder API takes a Hadoop {@code Configuration}, and Hadoop's own
 * {@code RawLocalFileSystem} needs {@code winutils.exe} on Windows -- which no agency, and no
 * developer, should have to install. The question is whether routing through parquet-java's
 * pure-NIO {@link LocalOutputFile} avoids the Hadoop filesystem layer entirely.
 *
 * <p>This test exists to answer that. If it fails, bronze cannot use Parquet as specified and the
 * deviation goes to Jeff rather than being worked around quietly.
 */
class ParquetOnWindowsSpikeTest {

    @TempDir
    Path dir;

    private static final Schema ENVELOPE = SchemaBuilder.record("RawEnvelope")
            .namespace("gov.niemplatform.storage")
            .fields()
            .requiredString("sourceId")
            .requiredLong("ingestEpochMillis")
            .requiredBytes("payload")
            .requiredString("contentHash")
            .endRecord();

    @Test
    @DisplayName("Parquet writes and reads through pure NIO, with no Hadoop filesystem involved")
    void parquetRoundTripsWithoutHadoopFileSystem() throws Exception {
        Path file = dir.resolve("bronze-000001.parquet");

        GenericRecord landed = new GenericData.Record(ENVELOPE);
        landed.put("sourceId", "riverton-pd-cad");
        landed.put("ingestEpochMillis", 1_772_000_000_000L);
        landed.put("payload", java.nio.ByteBuffer.wrap(
                "2026-000114,BURG,2026/03/04 11:20".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        landed.put("contentHash", "sha256:deadbeef");

        try (ParquetWriter<GenericRecord> writer = AvroParquetWriter
                .<GenericRecord>builder(new LocalOutputFile(file))
                .withSchema(ENVELOPE)
                .withCompressionCodec(CompressionCodecName.SNAPPY)
                .build()) {
            writer.write(landed);
        }

        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.size(file)).isPositive();

        List<GenericRecord> read = new java.util.ArrayList<>();
        try (ParquetReader<GenericRecord> reader = AvroParquetReader
                .<GenericRecord>builder(new LocalInputFile(file))
                .build()) {
            GenericRecord next;
            while ((next = reader.read()) != null) {
                read.add(next);
            }
        }

        assertThat(read).singleElement().satisfies(record -> {
            assertThat(record.get("sourceId")).hasToString("riverton-pd-cad");
            assertThat(record.get("contentHash")).hasToString("sha256:deadbeef");
            byte[] payload = ((java.nio.ByteBuffer) record.get("payload")).array();
            assertThat(new String(payload, java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("2026-000114,BURG,2026/03/04 11:20");
        });
    }
}
