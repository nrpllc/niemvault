package gov.niemplatform.storage.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.aws.s3.S3FileIO;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericAppenderFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.jdbc.JdbcCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * Resolves ADR 0005 without deviating from the pinned decision or installing unsigned binaries.
 *
 * <p>{@code IcebergEmbeddedSpikeTest} established that Iceberg's {@code HadoopCatalog} needs
 * {@code winutils.exe} on Windows. This spike takes the other route: a JDBC catalog for metadata
 * and {@code S3FileIO} for data, so no Hadoop {@code FileSystem} is constructed at all. The client
 * is pure Java over HTTP, which runs natively anywhere, and an object store is what spec §2 names
 * for storage in the first place.
 *
 * <p>MinIO stands in for the object store, in a container, which is already the convention for
 * integration tests (§9). In a real deployment the same code points at whatever S3-compatible
 * store the agency runs -- including an on-premises one, which matters for the air-gapped
 * delivery mode.
 */
@Tag("docker")
@Testcontainers
class IcebergOnObjectStoreSpikeTest {

    private static final String BUCKET = "niem-silver";
    private static final String ACCESS_KEY = "niemplatform";
    private static final String SECRET_KEY = "niemplatform-secret";

    @Container
    private static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2024-11-07T00-52-20Z")
            .withUserName(ACCESS_KEY)
            .withPassword(SECRET_KEY);

    private static final Schema CANONICAL_PERSON = new Schema(
            Types.NestedField.required(1, "canonicalId", Types.StringType.get()),
            Types.NestedField.optional(2, "surName", Types.StringType.get()),
            Types.NestedField.optional(3, "givenName", Types.StringType.get()),
            Types.NestedField.optional(4, "birthDate", Types.StringType.get()));

    private static void createBucket() {
        try (S3Client client = S3Client.builder()
                .endpointOverride(java.net.URI.create(MINIO.getS3URL()))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .build()) {
            client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException ignored) {
            // Re-running against a warm container is fine.
        }
    }

    private static JdbcCatalog catalog(String name) {
        createBucket();
        Map<String, String> properties = new HashMap<>();
        // Metadata in an embedded database, data in the object store. Neither path touches Hadoop.
        properties.put(CatalogProperties.CATALOG_IMPL, JdbcCatalog.class.getName());
        properties.put(CatalogProperties.URI, "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        properties.put(CatalogProperties.WAREHOUSE_LOCATION, "s3://" + BUCKET + "/silver");
        properties.put(CatalogProperties.FILE_IO_IMPL, S3FileIO.class.getName());
        properties.put("s3.endpoint", MINIO.getS3URL());
        properties.put("s3.access-key-id", ACCESS_KEY);
        properties.put("s3.secret-access-key", SECRET_KEY);
        properties.put("s3.path-style-access", "true");
        properties.put("client.region", "us-east-1");

        JdbcCatalog catalog = new JdbcCatalog();
        catalog.initialize(name, properties);
        return catalog;
    }

    @Test
    @DisplayName("Iceberg creates, appends to, and reads a table with no Hadoop filesystem")
    void icebergRunsOnAnObjectStore() throws Exception {
        try (JdbcCatalog catalog = catalog("basic")) {
            catalog.createNamespace(Namespace.of("canonical"));
            Table table = catalog.createTable(
                    TableIdentifier.of(Namespace.of("canonical"), "person"),
                    CANONICAL_PERSON, PartitionSpec.unpartitioned());

            append(table, "cluster-1", "DOE", "JANE", "1988-03-14");

            assertThat(readAll(table)).singleElement().satisfies(record ->
                    assertThat(record.getField("surName")).isEqualTo("DOE"));
        }
    }

    @Test
    @DisplayName("snapshot time travel works, which is why spec §2 pinned a table format at all")
    void timeTravelAcrossSnapshots() throws Exception {
        try (JdbcCatalog catalog = catalog("timetravel")) {
            catalog.createNamespace(Namespace.of("canonical"));
            Table table = catalog.createTable(
                    TableIdentifier.of(Namespace.of("canonical"), "person"),
                    CANONICAL_PERSON, PartitionSpec.unpartitioned());

            append(table, "cluster-1", "DOE", "JANE", "1988-03-14");
            table.refresh();
            long afterFirst = table.currentSnapshot().snapshotId();

            append(table, "cluster-2", "RIVERA", "LUIS", "1975-11-02");
            table.refresh();

            assertThat(readAll(table)).hasSize(2);

            List<Record> earlier = new java.util.ArrayList<>();
            IcebergGenerics.read(table).useSnapshot(afterFirst).build().forEach(earlier::add);
            assertThat(earlier)
                    .as("silver as it was at an earlier version, which is what makes replay verifiable")
                    .hasSize(1);
        }
    }

    private static void append(Table table, String clusterId, String surname, String given, String birthDate)
            throws Exception {
        GenericRecord person = GenericRecord.create(CANONICAL_PERSON);
        person.setField("canonicalId", clusterId);
        person.setField("surName", surname);
        person.setField("givenName", given);
        person.setField("birthDate", birthDate);

        OutputFile file = table.io().newOutputFile(
                table.locationProvider().newDataLocation(clusterId + ".parquet"));
        GenericAppenderFactory appenders = new GenericAppenderFactory(table.schema());

        try (DataWriter<Record> writer = appenders.newDataWriter(
                org.apache.iceberg.encryption.EncryptedFiles.plainAsEncryptedOutput(file),
                org.apache.iceberg.FileFormat.PARQUET, null)) {
            writer.write(person);
        }
        table.newAppend()
                .appendFile(org.apache.iceberg.DataFiles.builder(table.spec())
                        .withPath(file.location())
                        .withFileSizeInBytes(table.io().newInputFile(file.location()).getLength())
                        .withFormat(org.apache.iceberg.FileFormat.PARQUET)
                        .withRecordCount(1)
                        .build())
                .commit();
    }

    private static List<Record> readAll(Table table) {
        List<Record> records = new java.util.ArrayList<>();
        IcebergGenerics.read(table).build().forEach(records::add);
        return records;
    }
}
