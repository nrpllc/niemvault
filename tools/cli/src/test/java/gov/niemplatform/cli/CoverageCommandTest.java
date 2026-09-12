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
 * {@code niem coverage}, the answer to "conformant to how much of NIEM?".
 *
 * <p>Runs against the release packaged with the build rather than a fixture. A coverage report
 * drawn from a stub release would pass while the real one was missing from the jar, which is the
 * one failure this command cannot be allowed to have.
 */
@DisplayName("niem coverage")
class CoverageCommandTest {

    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;

    private int run(String... args) {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        java.io.PrintWriter outWriter = new java.io.PrintWriter(out, true, StandardCharsets.UTF_8);
        java.io.PrintWriter errWriter = new java.io.PrintWriter(err, true, StandardCharsets.UTF_8);

        CommandLine command = new CommandLine(new NiemCli());
        command.setOut(outWriter);
        command.setErr(errWriter);
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
            outWriter.flush();
            errWriter.flush();
        }
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("the summary")
    class Summary {

        @Test
        @DisplayName("reports the whole release, used domains first")
        void reportsEveryNamespace() {
            assertThat(run("coverage")).isZero();

            assertThat(stdout()).contains("niem-core", "justice", "maritime", "biometrics");
            // The cited domains lead and the untouched ones follow. Which of the cited ones leads
            // depends on what the model currently declares -- niem-core when it was the CAD slice,
            // justice once the criminal history cycle landed -- so the order between them is not
            // asserted here; doing so would make every honest model change a failing ordering test.
            assertThat(stdout()).containsSubsequence("justice", "agriculture");
            assertThat(stdout()).containsSubsequence("niem-core", "agriculture");
        }

        @Test
        @DisplayName("keeps the denominator visible, so coverage is a fraction of something")
        void showsTheDenominator() {
            // "2 of 18 namespaces" is the honest answer and the useful one. A report of only the
            // two used namespaces would say the model covers all of NIEM it has heard of.
            assertThat(run("coverage")).isZero();
            assertThat(stdout()).contains("of 18 namespaces");
        }

        @Test
        @DisplayName("prints each extension with its written reason, not just its name")
        void printsExtensionsWithReasons() {
            assertThat(run("coverage")).isZero();

            assertThat(stdout()).contains("Extensions beyond NIEM");
            assertThat(stdout()).contains("Incident.beat");
            // The reason, not merely the fact. An extension listed bare reads as a gap.
            assertThat(stdout()).contains("operational geography");
        }

        @Test
        @DisplayName("--used-only drops the domains the model never goes near")
        void usedOnlyNarrowsIt() {
            assertThat(run("coverage", "--used-only")).isZero();

            assertThat(stdout()).contains("niem-core", "justice");
            assertThat(stdout()).doesNotContain("maritime");
        }
    }

    @Nested
    @DisplayName("one namespace")
    class OneNamespace {

        @Test
        @DisplayName("lists every citation into it, by prefix")
        void listsCitationsByPrefix() {
            assertThat(run("coverage", "--namespace", "nc")).isZero();

            assertThat(stdout()).contains("Person.birthDate", "PersonBirthDate");
            assertThat(stdout()).contains("type", "PersonType");
        }

        @Test
        @DisplayName("is reachable by domain name as well as prefix")
        void listsCitationsByName() {
            assertThat(run("coverage", "--namespace", "justice")).isZero();
            assertThat(stdout()).contains("Person.sexCode", "PersonSexCode");
        }

        @Test
        @DisplayName("says plainly when the model does not stand on it")
        void reportsAnUntouchedNamespace() {
            assertThat(run("coverage", "--namespace", "maritime")).isZero();
            assertThat(stdout()).contains("does not stand on this namespace");
        }

        @Test
        @DisplayName("fails rather than reporting nothing when the name is wrong")
        void unknownNamespaceFails() {
            // Exit non-zero: a governance job asking about a namespace that does not exist has a
            // bug, and a zero exit would let it report a clean coverage check for nothing at all.
            assertThat(run("coverage", "--namespace", "nonesuch")).isEqualTo(1);
            assertThat(err.toString(StandardCharsets.UTF_8)).contains("No namespace in the release");
        }
    }

    @Nested
    @DisplayName("as JSON")
    class AsJson {

        @Test
        @DisplayName("carries the counts, the citations, and the extensions")
        void emitsStructuredCoverage() throws Exception {
            assertThat(run("coverage", "--json")).isZero();

            JsonNode report = new ObjectMapper().readTree(stdout());
            assertThat(report.get("namespaces").asInt()).isEqualTo(18);
            assertThat(report.get("namespacesTouched").asInt()).isEqualTo(2);
            assertThat(report.get("cited").asInt()).isPositive();
            assertThat(report.get("declared").asInt()).isGreaterThan(27_000);
            assertThat(report.get("extensions")).isNotEmpty();

            // Empty because the build refuses to generate a model containing one. Asserted so that
            // a release drifting from the one the build verified against fails here.
            assertThat(report.get("unresolved")).isEmpty();
        }
    }
}
