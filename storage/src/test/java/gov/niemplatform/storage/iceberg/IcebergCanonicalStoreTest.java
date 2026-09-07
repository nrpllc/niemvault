package gov.niemplatform.storage.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalFieldDescriptor;
import gov.niemplatform.canonical.meta.CanonicalId;
import gov.niemplatform.canonical.meta.CanonicalKind;
import gov.niemplatform.canonical.meta.CanonicalRef;
import gov.niemplatform.canonical.meta.CanonicalRoleDescriptor;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.canonical.meta.ExtensionJustification;
import gov.niemplatform.canonical.meta.FieldType;
import gov.niemplatform.canonical.meta.NiemProvenance;
import gov.niemplatform.storage.api.SilverCommit;
import gov.niemplatform.storage.api.SilverSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * Canonical silver on Iceberg (spec §2, ADR 0005).
 *
 * <p>Two properties here are load-bearing rather than incidental, and both feed acceptance
 * criterion 6. Round-trip fidelity: a record read back must equal the record written, or a correct
 * replay would look wrong. And drop-then-rebuild: silver must be removable and reconstructible,
 * which is exactly what the criterion asks the platform to demonstrate.
 */
@Tag("docker")
@Testcontainers
class IcebergCanonicalStoreTest {

    private static final String BUCKET = "niem-silver";
    private static final String ACCESS_KEY = "niemplatform";
    private static final String SECRET_KEY = "niemplatform-secret";
    private static final String NIEM_CORE =
            "https://docs.oasis-open.org/niemopen/ns/model/niem-core/6.0/";

    @Container
    private static final MinIOContainer MINIO =
            new MinIOContainer("minio/minio:RELEASE.2024-11-07T00-52-20Z")
                    .withUserName(ACCESS_KEY)
                    .withPassword(SECRET_KEY);

    private IcebergCanonicalStore store;
    private static int catalogSequence;

    /** A Person-like type exercising every field type the canonical model has. */
    private static CanonicalTypeDescriptor personType() {
        return new CanonicalTypeDescriptor(
                "Person",
                "https://niemplatform.gov/canonical/core/1.0",
                "1.0.0",
                CanonicalKind.ENTITY,
                new NiemProvenance(NIEM_CORE, "nc:PersonType", null),
                null,
                List.of(
                        field("surName", FieldType.STRING, false),
                        field("birthDate", FieldType.DATE, false),
                        field("recordedAt", FieldType.DATE_TIME, false),
                        field("priorContacts", FieldType.INTEGER, false),
                        field("riskScore", FieldType.DECIMAL, false),
                        field("underSupervision", FieldType.BOOLEAN, false),
                        new CanonicalFieldDescriptor("sexCode", FieldType.CODE, false, false,
                                new NiemProvenance(NIEM_CORE, null, "nc:PersonSexCode"), null,
                                List.of("M", "F", "X", "U"), null),
                        field("aliases", FieldType.STRING, true)),
                List.of());
    }

    /** An association, so reference round-tripping is covered too. */
    private static CanonicalTypeDescriptor associationType() {
        return new CanonicalTypeDescriptor(
                "PersonIncidentAssociation",
                "https://niemplatform.gov/canonical/extension/core/1.0",
                "1.0.0",
                CanonicalKind.ASSOCIATION,
                null,
                new ExtensionJustification("Pending verification of the NIEM association type."),
                List.of(new CanonicalFieldDescriptor("involvementCode", FieldType.CODE, true, false,
                        null, new ExtensionJustification("Agency role vocabulary."),
                        List.of("VICTIM", "SUSPECT", "WITNESS"), null)),
                List.of(
                        new CanonicalRoleDescriptor("person", "Person", null,
                                new ExtensionJustification("Role of the extension association.")),
                        new CanonicalRoleDescriptor("incident", "Incident", null,
                                new ExtensionJustification("Role of the extension association."))));
    }

    private static CanonicalFieldDescriptor field(String name, FieldType type, boolean repeated) {
        return new CanonicalFieldDescriptor(name, type, false, repeated,
                new NiemProvenance(NIEM_CORE, null, "nc:" + name), null, List.of(), null);
    }

    private static Record person(String clusterId, String surname) {
        return Record.builder(personType().qualifiedName())
                .set("canonicalId", CanonicalId.of(clusterId))
                .set("surName", surname)
                .set("birthDate", LocalDate.of(1988, 3, 14))
                .set("recordedAt", Instant.parse("2026-03-04T18:20:00Z"))
                .set("priorContacts", 3L)
                .set("riskScore", new BigDecimal("0.75"))
                .set("underSupervision", Boolean.TRUE)
                .set("sexCode", "F")
                .set("aliases", List.of("JD", "JANIE"))
                .build();
    }

    @BeforeEach
    void setUp() {
        createBucket();
        store = new IcebergCanonicalStore(new IcebergCanonicalStoreConfig(
                "silver",
                "jdbc:h2:mem:silver" + (catalogSequence++) + ";DB_CLOSE_DELAY=-1",
                "s3://" + BUCKET + "/silver-" + catalogSequence,
                MINIO.getS3URL(),
                ACCESS_KEY,
                SECRET_KEY,
                "us-east-1"));
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

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
            // A warm container between tests is fine.
        }
    }

    @Test
    @DisplayName("a catalogue can be opened again, which a scheduled ingest does every time")
    void catalogueReopens() {
        // Every other test here uses a fresh catalogue, which hid a real defect for a long time:
        // H2 folds unquoted identifiers to upper case, so Iceberg looked for `iceberg_tables`, was
        // told it did not exist, and issued CREATE TABLE -- which failed, because it did. The store
        // was write-once, and the second `niem run` against the same catalogue would have failed.
        String uri = "jdbc:h2:mem:reopen" + (catalogSequence++) + ";DB_CLOSE_DELAY=-1";
        String warehouse = "s3://" + BUCKET + "/reopen-" + catalogSequence;
        CanonicalTypeDescriptor person = personType();

        try (var first = new IcebergCanonicalStore(new IcebergCanonicalStoreConfig(
                "silver", uri, warehouse, MINIO.getS3URL(), ACCESS_KEY, SECRET_KEY, "us-east-1"))) {
            first.ensureTable(person);
            first.append(person, List.of(person("C-1", "DOE")));
        }

        try (var second = new IcebergCanonicalStore(new IcebergCanonicalStoreConfig(
                "silver", uri, warehouse, MINIO.getS3URL(), ACCESS_KEY, SECRET_KEY, "us-east-1"))) {
            assertThat(second.count(person)).as("what the first open wrote is still there").isEqualTo(1);
            second.append(person, List.of(person("C-2", "ROE")));
            assertThat(second.count(person)).as("the second open appends, it does not replace").isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a canonical record survives a round trip through silver unchanged")
    void roundTripIsFaithful() {
        CanonicalTypeDescriptor person = personType();
        Record written = person("cluster-1", "DOE");

        store.append(person, List.of(written));

        try (Stream<Record> read = store.read(person)) {
            assertThat(read.toList())
                    .as("criterion 6 compares replayed silver against the original; a value that "
                            + "changed shape in storage would make a correct replay look wrong")
                    .containsExactly(written);
        }
    }

    @Test
    @DisplayName("every canonical field type round-trips, including the awkward ones")
    void allFieldTypesRoundTrip() {
        CanonicalTypeDescriptor person = personType();
        store.append(person, List.of(person("cluster-1", "DOE")));

        try (Stream<Record> read = store.read(person)) {
            Record back = read.toList().getFirst();
            assertThat(back.get("birthDate", LocalDate.class)).isEqualTo(LocalDate.of(1988, 3, 14));
            assertThat(back.get("recordedAt", Instant.class))
                    .isEqualTo(Instant.parse("2026-03-04T18:20:00Z"));
            assertThat(back.get("priorContacts", Long.class)).isEqualTo(3L);
            assertThat(back.get("riskScore", BigDecimal.class))
                    .isEqualByComparingTo(new BigDecimal("0.75"));
            assertThat(back.get("underSupervision", Boolean.class)).isTrue();
            assertThat(back.getList("aliases", String.class)).containsExactly("JD", "JANIE");
        }
    }

    @Test
    @DisplayName("an association's role references keep their target type")
    void referencesRoundTrip() {
        CanonicalTypeDescriptor association = associationType();
        Record written = Record.builder(association.qualifiedName())
                .set("canonicalId", CanonicalId.of("PIA/1"))
                .set("person", CanonicalRef.to("Person", "cluster-1"))
                .set("incident", CanonicalRef.to("Incident", "INC/1"))
                .set("involvementCode", "VICTIM")
                .build();

        store.append(association, List.of(written));

        try (Stream<Record> read = store.read(association)) {
            Record back = read.toList().getFirst();
            assertThat(back).isEqualTo(written);
            assertThat(back.get("person", CanonicalRef.class).typeName())
                    .as("a reference is two facts; flattening them would need a parsing rule "
                            + "nobody wrote down")
                    .isEqualTo("Person");
        }
    }

    @Test
    @DisplayName("an absent value comes back absent, not as an empty string or an empty list")
    void absenceIsPreserved() {
        CanonicalTypeDescriptor person = personType();
        Record sparse = Record.builder(person.qualifiedName())
                .set("canonicalId", CanonicalId.of("cluster-2"))
                .set("surName", "RIVERA")
                .build();

        store.append(person, List.of(sparse));

        try (Stream<Record> read = store.read(person)) {
            Record back = read.toList().getFirst();
            assertThat(back.hasValue("birthDate")).isFalse();
            assertThat(back.getList("aliases", String.class)).isEmpty();
        }
    }

    @Nested
    @DisplayName("time travel")
    class TimeTravel {

        @Test
        @DisplayName("silver at an earlier commit is readable, which is what makes replay checkable")
        void readsAnEarlierSnapshot() {
            CanonicalTypeDescriptor person = personType();
            SilverCommit first = store.append(person, List.of(person("cluster-1", "DOE")));
            store.append(person, List.of(person("cluster-2", "RIVERA")));

            assertThat(store.count(person)).isEqualTo(2);
            try (Stream<Record> earlier = store.readAsOf(person, first.snapshotId())) {
                assertThat(earlier.toList()).hasSize(1);
            }
        }

        @Test
        @DisplayName("history reports every commit in order")
        void historyIsOrdered() {
            CanonicalTypeDescriptor person = personType();
            store.append(person, List.of(person("cluster-1", "DOE")));
            store.append(person, List.of(person("cluster-2", "RIVERA")));

            List<SilverSnapshot> history = store.history(person);

            assertThat(history).hasSize(2);
            assertThat(history).extracting(SilverSnapshot::recordCount).containsExactly(1L, 2L);
        }

        @Test
        @DisplayName("an empty append does not add a point to history that nothing happened at")
        void emptyAppendIsANoOp() {
            CanonicalTypeDescriptor person = personType();
            store.append(person, List.of(person("cluster-1", "DOE")));

            store.append(person, List.of());

            assertThat(store.history(person)).hasSize(1);
        }
    }

    @Test
    @DisplayName("silver can be dropped and rebuilt, which criterion 6 requires")
    void dropAndRebuild() {
        CanonicalTypeDescriptor person = personType();
        List<Record> original = List.of(person("cluster-1", "DOE"), person("cluster-2", "RIVERA"));
        store.append(person, original);
        assertThat(store.count(person)).isEqualTo(2);

        store.drop(person);
        assertThat(store.types()).doesNotContain("person");
        assertThat(store.count(person)).isZero();

        store.append(person, original);

        try (Stream<Record> rebuilt = store.read(person)) {
            assertThat(rebuilt.toList())
                    .as("bronze is never deleted; silver is derived, so deleting and rebuilding it "
                            + "is exactly what the platform must be able to prove")
                    .containsExactlyInAnyOrderElementsOf(original);
        }
    }

    @Test
    @DisplayName("the table schema is derived from the model, so it cannot drift from it")
    void schemaComesFromTheDescriptor() {
        CanonicalTypeDescriptor person = personType();
        store.ensureTable(person);

        assertThat(store.types()).contains("person");
        // Creating twice is not an error; a run that starts against an existing store is normal.
        store.ensureTable(person);
        assertThat(store.types()).containsOnlyOnce("person");
    }

    @Test
    @DisplayName("reading a type that was never written is empty, not an error")
    void unwrittenTypeIsEmpty() {
        try (Stream<Record> read = store.read(associationType())) {
            assertThat(read.toList()).isEmpty();
        }
        assertThat(store.history(associationType())).isEmpty();
    }
}
