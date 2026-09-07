package gov.niemplatform.storage.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericAppenderFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.hadoop.HadoopCatalog;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Decides the open half of ADR 0005: can Iceberg run embedded on a developer machine?
 *
 * <p>Spec §2 pins canonical silver storage to Delta Lake or Iceberg, citing time travel as the
 * reason -- it is what makes lineage and replay verifiable rather than asserted. Bronze already
 * proved that Parquet itself works on Windows without a Hadoop installation
 * ({@code ParquetOnWindowsSpikeTest}), but Iceberg is a harder case: its catalog and file IO reach
 * for a Hadoop {@code FileSystem} rather than merely a {@code Configuration}, and Hadoop's local
 * filesystem historically wants {@code winutils.exe}.
 *
 * <p>If this passes, silver ships as spec §2 pins it. If it fails, the choice between
 * containerised-only local development and deviating from a pinned decision goes to Jeff rather
 * than being made quietly.
 *
 * <p>Also exercises snapshot time travel specifically, because that is the property the spec cites
 * as the reason for the decision, and acceptance criterion 6 depends on it.
 */
@Disabled("Answered, and the answer is no. Iceberg's HadoopCatalog calls Shell.getWinUtilsPath "
        + "and fails on Windows without winutils.exe. Kept as the record of that finding -- see "
        + "ADR 0005 -- and superseded by IcebergOnObjectStoreSpikeTest, which reaches Iceberg "
        + "through S3FileIO and touches no Hadoop filesystem at all.")
class IcebergEmbeddedSpikeTest {

    @TempDir
    Path warehouse;

    private static final Schema CANONICAL_PERSON = new Schema(
            Types.NestedField.required(1, "canonicalId", Types.StringType.get()),
            Types.NestedField.optional(2, "surName", Types.StringType.get()),
            Types.NestedField.optional(3, "givenName", Types.StringType.get()),
            Types.NestedField.optional(4, "birthDate", Types.StringType.get()));

    @Test
    @DisplayName("Iceberg creates, appends to, and reads a table on the local filesystem")
    void icebergRunsEmbedded() throws Exception {
        try (HadoopCatalog catalog = new HadoopCatalog()) {
            catalog.setConf(new Configuration());
            catalog.initialize("silver", Map.of(
                    CatalogProperties.WAREHOUSE_LOCATION, warehouse.toUri().toString()));

            TableIdentifier id = TableIdentifier.of(Namespace.of("canonical"), "person");
            Table table = catalog.createTable(id, CANONICAL_PERSON, PartitionSpec.unpartitioned());

            appendPerson(table, "cluster-1", "DOE", "JANE", "1988-03-14");

            List<Record> read = readAll(table);
            assertThat(read).singleElement().satisfies(record ->
                    assertThat(record.getField("surName")).isEqualTo("DOE"));
        }
    }

    @Test
    @DisplayName("snapshot time travel works, which is the reason the spec pinned a table format")
    void timeTravelAcrossSnapshots() throws Exception {
        try (HadoopCatalog catalog = new HadoopCatalog()) {
            catalog.setConf(new Configuration());
            catalog.initialize("silver", Map.of(
                    CatalogProperties.WAREHOUSE_LOCATION, warehouse.toUri().toString()));

            TableIdentifier id = TableIdentifier.of(Namespace.of("canonical"), "person");
            Table table = catalog.createTable(id, CANONICAL_PERSON, PartitionSpec.unpartitioned());

            appendPerson(table, "cluster-1", "DOE", "JANE", "1988-03-14");
            table.refresh();
            long afterFirst = table.currentSnapshot().snapshotId();

            appendPerson(table, "cluster-2", "RIVERA", "LUIS", "1975-11-02");
            table.refresh();

            assertThat(readAll(table)).hasSize(2);

            List<Record> asOfFirst = new java.util.ArrayList<>();
            IcebergGenerics.read(table).useSnapshot(afterFirst).build().forEach(asOfFirst::add);
            assertThat(asOfFirst)
                    .as("silver at an earlier version, which is what makes replay verifiable")
                    .hasSize(1);
        }
    }

    private static void appendPerson(
            Table table, String clusterId, String surname, String givenName, String birthDate)
            throws Exception {
        GenericRecord person = GenericRecord.create(CANONICAL_PERSON);
        person.setField("canonicalId", clusterId);
        person.setField("surName", surname);
        person.setField("givenName", givenName);
        person.setField("birthDate", birthDate);

        GenericAppenderFactory appenders = new GenericAppenderFactory(table.schema());
        OutputFile file = table.io().newOutputFile(
                table.locationProvider().newDataLocation(clusterId + ".parquet"));

        try (DataWriter<Record> writer = appenders.newDataWriter(
                org.apache.iceberg.encryption.EncryptedFiles.plainAsEncryptedOutput(file),
                org.apache.iceberg.FileFormat.PARQUET,
                null)) {
            writer.write(person);
        }
        table.newAppend().appendFile(writeResult(table, file)).commit();
    }

    private static org.apache.iceberg.DataFile writeResult(Table table, OutputFile file) {
        return org.apache.iceberg.DataFiles.builder(table.spec())
                .withPath(file.location())
                .withFileSizeInBytes(table.io().newInputFile(file.location()).getLength())
                .withFormat(org.apache.iceberg.FileFormat.PARQUET)
                .withRecordCount(1)
                .build();
    }

    private static List<Record> readAll(Table table) {
        List<Record> records = new java.util.ArrayList<>();
        IcebergGenerics.read(table).build().forEach(records::add);
        return records;
    }
}
