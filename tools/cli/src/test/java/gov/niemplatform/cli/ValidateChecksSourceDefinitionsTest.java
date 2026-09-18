package gov.niemplatform.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * {@code niem validate} over a module's source definitions.
 *
 * <p>A source definition was the one content artifact nothing checked until {@code run} loaded it,
 * which put a misspelled setting on the far side of a scheduled job: the operator finds out when
 * the nightly load does not run, at the hour it was meant to run. Every other artifact in a module
 * is checked at deploy time by this command, and the check is the loader the runtime itself uses --
 * never a second one (ADR 0020).
 *
 * <p>Well-formedness only. Whether the broker answers is {@code health()}, asked by {@code run}
 * before it lands anything; a records manager reviewing a definition on a laptop has no broker, and
 * a validate that demanded one would make a source impossible to review away from its environment.
 */
@DisplayName("niem validate, over source definitions")
class ValidateChecksSourceDefinitionsTest {

    @TempDir
    Path work;

    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;

    private int run(String... args) {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();

        CommandLine command = new CommandLine(new NiemCli());
        command.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));

        PrintStream systemOut = System.out;
        PrintStream systemErr = System.err;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            return command.execute(args);
        } finally {
            System.setOut(systemOut);
            System.setErr(systemErr);
        }
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private void copyResource(String resource, Path destination) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("missing test resource " + resource);
            }
            Files.copy(stream, destination);
        }
    }

    /** A module with real mappings and contracts, and no sources/ directory yet. */
    private Path moduleDirectory() throws IOException {
        Path module = Files.createDirectories(work.resolve("module"));
        Files.createDirectories(module.resolve("mappings"));
        Files.createDirectories(module.resolve("contracts"));
        copyResource("/module.yaml", module.resolve("module.yaml"));
        copyResource("/mappings/cad-to-canonical-1.0.0.yaml",
                module.resolve("mappings").resolve("cad-to-canonical-1.0.0.yaml"));
        for (String contract : List.of(
                "cad-incident-to-canonical-1.0.0.yaml",
                "cad-person-to-canonical-1.0.0.yaml",
                "cad-association-to-canonical-1.0.0.yaml")) {
            copyResource("/contracts/" + contract, module.resolve("contracts").resolve(contract));
        }
        return module;
    }

    private Path sources(Path module) throws IOException {
        return Files.createDirectories(module.resolve("sources"));
    }

    private Path writeSource(Path module, String fileName, String yaml) throws IOException {
        Path file = sources(module).resolve(fileName);
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return file;
    }

    @Nested
    @DisplayName("a module whose transports are sound")
    class Sound {

        @Test
        @DisplayName("validates the shipped file drop and says what it checked")
        void validatesTheShippedFileDrop() throws IOException {
            Path module = moduleDirectory();
            copyResource("/sources/riverton-cad-file-drop.yaml",
                    sources(module).resolve("riverton-cad-file-drop.yaml"));

            assertThat(run("validate", "--module", module.toString())).isZero();

            assertThat(stdout()).contains("riverton-cad-file-drop.yaml");
            assertThat(stdout()).contains("riverton-pd-cad", "file-drop");
            // The transport's own answers, not the file's: retention is configuration for one
            // transport and a constant for another, and only the connector knows which.
            assertThat(stdout()).contains("POLL", "RETAINED");
        }

        @Test
        @DisplayName("validates a Kafka source without a broker, because configuring is not reaching")
        void validatesKafkaWithoutABroker() throws IOException {
            Path module = moduleDirectory();
            writeSource(module, "cad-kafka.yaml", """
                    sourceId: riverton-pd-cad
                    connectorInstanceId: cad-kafka-1
                    type: kafka
                    freshnessSla: PT15M
                    settings:
                      bootstrapServers: broker.invalid:9092
                      topic: cad.incidents
                      groupId: niem-ingest-riverton
                      retention: retained
                    """);

            assertThat(run("validate", "--module", module.toString())).isZero();
            assertThat(stdout()).contains("cad-kafka.yaml", "kafka", "PUSH", "RETAINED");
        }

        @Test
        @DisplayName("says so when no mapping reads the source, without failing the module")
        void saysWhenNoMappingReadsTheSource() throws IOException {
            Path module = moduleDirectory();
            writeSource(module, "leon-cad.yaml", """
                    sourceId: leon-so-cad
                    connectorInstanceId: leon-cad-file-drop-1
                    type: file-drop
                    settings:
                      directory: ./drop
                      filePattern: "*.csv"
                    """);

            // Not fatal: the definition is correct, and a transport described before its mapping is
            // written is the order onboarding actually happens in -- the agency configures the feed,
            // the steward maps it afterwards. riverton-rms-cdc ships in exactly that state
            // (ADR 0032). Failing would make the module unable to say the feed exists.
            assertThat(run("validate", "--module", module.toString())).isZero();
            assertThat(stdout()).contains("leon-so-cad");
            assertThat(stdout()).contains("no mapping in this module reads");
        }

        @Test
        @DisplayName("a module shipping no transports at all is not a failure")
        void noSourcesDirectoryIsNotAFailure() throws IOException {
            Path module = moduleDirectory();

            // Transport configuration may equally live in a deployment's own repository rather than
            // in the module. Demanding it here would make that arrangement fail validation.
            assertThat(run("validate", "--module", module.toString())).isZero();
        }
    }

    @Nested
    @DisplayName("a definition that would fail at run time")
    class WouldFail {

        @Test
        @DisplayName("catches a misspelled setting, which a connector would otherwise never be told about")
        void catchesAMisspelledSetting() throws IOException {
            Path module = moduleDirectory();
            writeSource(module, "cad-kafka.yaml", """
                    sourceId: riverton-pd-cad
                    connectorInstanceId: cad-kafka-1
                    type: kafka
                    settings:
                      bootstrapServers: broker.invalid:9092
                      topic: cad.incidents
                      groupID: niem-ingest-riverton
                      retention: retained
                    """);

            assertThat(run("validate", "--module", module.toString())).isEqualTo(1);
            assertThat(stderr()).contains("groupID");
        }

        @Test
        @DisplayName("catches a retention nobody stated, rather than letting it default to lawful")
        void catchesAnUnstatedRetention() throws IOException {
            Path module = moduleDirectory();
            writeSource(module, "cad-kafka.yaml", """
                    sourceId: riverton-pd-cad
                    connectorInstanceId: cad-kafka-1
                    type: kafka
                    settings:
                      bootstrapServers: broker.invalid:9092
                      topic: cad.incidents
                      groupId: niem-ingest-riverton
                    """);

            // ADR 0027. The whole reason retention has no default is that an author who did not
            // think about it would silently land data that may not lawfully be kept -- so the
            // omission has to be findable before a run, not after one.
            assertThat(run("validate", "--module", module.toString())).isEqualTo(1);
            assertThat(stderr()).contains("retention");
        }

        @Test
        @DisplayName("names what this deployment can read when the transport is not on the classpath")
        void namesWhatTheDeploymentCanRead() throws IOException {
            Path module = moduleDirectory();
            writeSource(module, "cad-mqtt.yaml", """
                    sourceId: riverton-pd-cad
                    connectorInstanceId: cad-mqtt-1
                    type: mqtt
                    settings:
                      broker: tcp://mqtt.invalid:1883
                    """);

            assertThat(run("validate", "--module", module.toString())).isEqualTo(1);
            // An air-gapped operator has to be able to tell a missing jar from a misspelled
            // transport, and only the list of what is present distinguishes them.
            assertThat(stderr()).contains("mqtt");
            assertThat(stderr()).contains("file-drop", "kafka");
        }

        @Test
        @DisplayName("reports every problem at once, across mappings and transports alike")
        void reportsEveryProblemAtOnce() throws IOException {
            Path module = moduleDirectory();
            writeSource(module, "a-kafka.yaml", """
                    sourceId: riverton-pd-cad
                    connectorInstanceId: cad-kafka-1
                    type: kafka
                    settings:
                      bootstrapServers: broker.invalid:9092
                      topic: cad.incidents
                      groupId: g
                    """);
            writeSource(module, "b-mqtt.yaml", """
                    sourceId: riverton-pd-cad
                    connectorInstanceId: cad-mqtt-1
                    type: mqtt
                    settings: {}
                    """);

            assertThat(run("validate", "--module", module.toString())).isEqualTo(1);
            // One pass, both fixes (spec §9). A validate that stopped at the first would take as
            // many runs to clear as the module has mistakes.
            assertThat(stderr()).contains("retention");
            assertThat(stderr()).contains("mqtt");
        }
    }

    @Nested
    @DisplayName("narrowing to one definition")
    class Narrowing {

        @Test
        @DisplayName("--source checks that definition alone")
        void checksThatDefinitionAlone() throws IOException {
            Path module = moduleDirectory();
            Path good = writeSource(module, "good.yaml", """
                    sourceId: riverton-pd-cad
                    connectorInstanceId: cad-file-drop-1
                    type: file-drop
                    settings:
                      directory: ./drop
                      filePattern: "*.csv"
                    """);
            writeSource(module, "broken.yaml", """
                    sourceId: riverton-pd-cad
                    connectorInstanceId: cad-mqtt-1
                    type: mqtt
                    settings: {}
                    """);

            assertThat(run("validate", "--module", module.toString(),
                    "--source", good.toString())).isZero();
            assertThat(stdout()).contains("good.yaml");
            assertThat(stdout()).doesNotContain("broken.yaml");
        }
    }
}
