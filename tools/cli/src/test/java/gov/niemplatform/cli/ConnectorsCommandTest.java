package gov.niemplatform.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * {@code niem connectors}, the answer to "what can this deployment actually read?".
 *
 * <p>Runs against the connectors packaged with the build rather than a stub registry. The failure
 * this command exists to prevent is a transport that is on the classpath and invisible, or named in
 * a source definition and absent -- and a test against a fixture registry would pass in both cases.
 */
@DisplayName("niem connectors")
class ConnectorsCommandTest {

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

    @Nested
    @DisplayName("the listing")
    class Listing {

        @Test
        @DisplayName("names every transport the deployment ships")
        void namesEveryTransport() {
            assertThat(run("connectors")).isZero();

            // Every connector the CLI's build file puts on the classpath. A transport added to that
            // list and missing here means ServiceLoader registration was forgotten -- which is
            // otherwise found by a source definition failing to resolve at run time.
            assertThat(stdout()).contains("file-drop", "kafka", "sftp", "ftps");
        }

        @Test
        @DisplayName("lists transports in a stable order, so two runs can be diffed")
        void listsInAStableOrder() {
            assertThat(run("connectors")).isZero();
            String first = stdout();

            assertThat(run("connectors")).isZero();
            assertThat(stdout()).isEqualTo(first);

            // Alphabetical, not discovery order: discovery order is classpath order, which is not
            // something an operator controls or can reason about.
            assertThat(stdout().indexOf("file-drop")).isLessThan(stdout().indexOf("ftp"));
            assertThat(stdout().indexOf("ftp")).isLessThan(stdout().indexOf("kafka"));
            assertThat(stdout().indexOf("kafka")).isLessThan(stdout().indexOf("sftp"));
        }

        @Test
        @DisplayName("says a retention the source decides is decided by the source, never guessing")
        void doesNotGuessRetention() {
            assertThat(run("connectors")).isZero();

            // ADR 0027: Kafka refuses to state a retention posture before it is configured, because
            // the same broker carries an agency's own feed and a state system's non-retainable
            // responses. A listing that printed RETAINED here would be inventing the answer to a
            // legal question, which is the whole failure the missing default exists to prevent.
            assertThat(stdout()).containsPattern("kafka\\s+PUSH\\s+per source");

            // A file drop's retention is a property of the transport, so it is known without one.
            assertThat(stdout()).containsPattern("file-drop\\s+POLL\\s+RETAINED");
        }

        @Test
        @DisplayName("points at the artifact that names a transport")
        void pointsAtTheArtifact() {
            assertThat(run("connectors")).isZero();

            // The command answers "what can I read"; the next question is always "how do I say so",
            // and the answer is a source definition's type: field rather than a flag.
            assertThat(stdout()).contains("type:");
        }
    }

    @Nested
    @DisplayName("as JSON")
    class AsJson {

        @Test
        @DisplayName("emits one object per transport, for a governance tool rather than a person")
        void emitsOneObjectPerTransport() throws Exception {
            assertThat(run("connectors", "--json")).isZero();

            JsonNode report = new ObjectMapper().readTree(stdout());
            assertThat(report.get("transports")).isNotNull();
            assertThat(report.get("transports").size()).isGreaterThanOrEqualTo(4);

            JsonNode kafka = null;
            for (JsonNode transport : report.get("transports")) {
                if ("kafka".equals(transport.get("type").asText())) {
                    kafka = transport;
                }
            }
            assertThat(kafka).isNotNull();
            assertThat(kafka.get("interactionMode").asText()).isEqualTo("PUSH");
            // Absent rather than invented, so a consumer cannot read a guess as a declaration.
            assertThat(kafka.get("retention").isNull()).isTrue();
            assertThat(kafka.get("retentionDecidedBy").asText()).isEqualTo("source");
        }
    }
}
