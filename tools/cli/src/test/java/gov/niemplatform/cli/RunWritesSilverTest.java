package gov.niemplatform.cli;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.canonical.core.CoreCanonicalTypes;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.storage.iceberg.IcebergCanonicalStore;
import gov.niemplatform.storage.iceberg.IcebergCanonicalStoreConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * An ingest actually reaching the canonical store.
 *
 * <p>This exists because it very nearly did not. {@code run} landed bronze, mapped records, and
 * discarded them, while printing a line saying the canonical store "is not built yet" — true when it
 * was written and false for a long time afterwards. Every unit test passed, because none of them
 * asked where the records went. It was found by running the container and reading the output.
 *
 * <p>So this asks the question those tests did not: after an ingest, is the data in silver.
 *
 * <h2>It runs the installed binary, in its own process</h2>
 *
 * <p>Not the command class. Two of the three problems found alongside the missing writes were in the
 * start script and the JVM flags, and neither exists when a test calls a method. Running the artifact
 * an operator runs is the only way any of them would have been caught.
 *
 * <p>It also delivers the object store secret the way production does — in the environment. The CLI
 * refuses it as an option on purpose, so passing it as one would exercise an interface the platform
 * does not offer. Setting an environment variable for the <em>current</em> JVM is not something the
 * JDK supports, and the reflective trick for it silently does nothing on Windows, where
 * {@code System.getenv(name)} reads a different internal map than the one that trick patches. A
 * child process has an environment by definition.
 *
 * <p>Tagged {@code docker}: it needs an object store.
 */
@Tag("docker")
@Testcontainers
class RunWritesSilverTest {

    private static final String BUCKET = "niem-silver";
    private static final String ACCESS_KEY = "niemplatform";
    private static final String SECRET_KEY = "niemplatform-secret";

    @Container
    private static final MinIOContainer MINIO =
            new MinIOContainer("minio/minio:RELEASE.2024-11-07T00-52-20Z")
                    .withUserName(ACCESS_KEY)
                    .withPassword(SECRET_KEY);

    @TempDir
    Path work;

    private Path module;
    private String catalogUri;

    /** What the last run printed. Reported on failure; a bare exit code says nothing. */
    private String output = "";

    private static int catalogSequence;

    @BeforeEach
    void prepare() throws IOException {
        createBucket();
        module = moduleDirectory();
        // A fresh catalogue per test: sharing one would let tests see each other's tables, and the
        // append assertion would depend on execution order.
        // No DB_CLOSE_DELAY. It holds an H2 database open until the JVM that opened it exits, so
        // reading the catalogue from this JVM would keep the file lock and the next ingest -- a
        // separate process -- could not open it. An operator would not set it on a file database
        // either, and a test that did would be exercising a configuration nobody runs.
        catalogUri = "jdbc:h2:file:"
                + work.resolve("catalog-" + (++catalogSequence)).toString().replace('\\', '/');
    }

    // --- running the real thing -------------------------------------------

    /** The installed CLI, as built by installDist and wired in by the build. */
    private static Path binary() {
        String install = System.getProperty("niem.install.dir");
        assertThat(install)
                .as("niem.install.dir must be set by the build; this test runs the installed binary")
                .isNotNull();
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        return Path.of(install, "bin", windows ? "niem.bat" : "niem");
    }

    private int niem(List<String> args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(binary().toString());
        command.addAll(args);

        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put(SilverOptions.S3_SECRET, SECRET_KEY);
        try {
            Process process = builder.start();
            // Read before waiting. A process whose output nobody drains blocks once the pipe fills,
            // and this one is chatty enough to reach that.
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("niem did not finish within five minutes");
            }
            return process.exitValue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for niem", interrupted);
        }
    }

    private int ingest() throws IOException {
        return niem(List.of(
                "run", "--tenant", "test.agency", "--engine", "DIRECT",
                "--module", module.toString(),
                "--mapping", module.resolve("mappings/cad-to-canonical-1.0.0.yaml").toString(),
                "--drop", dropDirectory().toString(),
                "--bronze", work.resolve("bronze").toString(),
                "--silver-catalog-uri", catalogUri,
                "--silver-warehouse", "s3://" + BUCKET + "/warehouse",
                "--s3-endpoint", MINIO.getS3URL(),
                "--s3-access-key-id", ACCESS_KEY));
    }

    /** What is in a canonical table now. */
    private long countIn(String typeName) {
        CanonicalTypeDescriptor descriptor = CoreCanonicalTypes.ALL.stream()
                .filter(type -> type.name().equals(typeName))
                .findFirst()
                .orElseThrow();
        try (var store = new IcebergCanonicalStore(new IcebergCanonicalStoreConfig(
                "niem", catalogUri, "s3://" + BUCKET + "/warehouse",
                MINIO.getS3URL(), ACCESS_KEY, SECRET_KEY, "us-east-1"))) {
            return store.count(descriptor);
        }
    }

    // --- the tests ---------------------------------------------------------

    @Test
    @DisplayName("an ingest puts its canonical records in silver, not only in bronze")
    void ingestWritesSilver() throws IOException {
        assertThat(ingest()).as("a clean ingest exits zero. Output:%n%s", output).isZero();

        // Ten source rows produce one Person, one Incident and one association each.
        assertThat(countIn("Person")).isEqualTo(10);
        assertThat(countIn("Incident")).isEqualTo(10);
        assertThat(countIn("PersonIncidentAssociation")).isEqualTo(10);
    }

    @Test
    @DisplayName("a second ingest appends rather than replacing what the first wrote")
    void ingestAppends() throws IOException {
        assertThat(ingest()).as("first ingest. Output:%n%s", output).isZero();
        assertThat(countIn("Incident")).isEqualTo(10);

        // The same rows again. An ingest adds what has arrived; replacing is replay's job, and an
        // ingest that replaced would silently discard everything landed before it.
        assertThat(ingest()).as("second ingest. Output:%n%s", output).isZero();
        assertThat(countIn("Incident")).isEqualTo(20);
    }

    @Test
    @DisplayName("without silver configured, an ingest says so rather than appearing to store data")
    void saysWhenSilverIsAbsent() throws IOException {
        // The exact failure this class exists for: mapped and discarded, looking healthy in every
        // log line except the one that matters.
        int exit = niem(List.of(
                "run", "--tenant", "test.agency", "--engine", "DIRECT",
                "--module", module.toString(),
                "--mapping", module.resolve("mappings/cad-to-canonical-1.0.0.yaml").toString(),
                "--drop", dropDirectory().toString(),
                "--bronze", work.resolve("bronze").toString()));

        assertThat(exit).as("Output:%n%s", output).isZero();
        assertThat(output).contains("Silver was not written").contains("can be replayed");
    }

    // --- fixtures ---------------------------------------------------------

    private Path moduleDirectory() throws IOException {
        Path directory = Files.createDirectories(work.resolve("module"));
        Files.createDirectories(directory.resolve("mappings"));
        Files.createDirectories(directory.resolve("contracts"));

        copyResource("/module.yaml", directory.resolve("module.yaml"));
        copyResource("/mappings/cad-to-canonical-1.0.0.yaml",
                directory.resolve("mappings/cad-to-canonical-1.0.0.yaml"));
        for (String contract : List.of(
                "cad-incident-to-canonical-1.0.0.yaml",
                "cad-person-to-canonical-1.0.0.yaml",
                "cad-association-to-canonical-1.0.0.yaml")) {
            copyResource("/contracts/" + contract, directory.resolve("contracts/" + contract));
        }
        return directory;
    }

    /** A fresh drop each time, because the connector moves what it lands. */
    private Path dropDirectory() throws IOException {
        Path drop = Files.createDirectories(work.resolve("drop-" + UUID.randomUUID()));
        copyResource("/fixtures/incidents.csv", drop.resolve("incidents.csv"));
        return drop;
    }

    private static void copyResource(String resource, Path target) throws IOException {
        try (InputStream stream = RunWritesSilverTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new AssertionError("missing test resource: " + resource);
            }
            Files.copy(stream, target);
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
}
