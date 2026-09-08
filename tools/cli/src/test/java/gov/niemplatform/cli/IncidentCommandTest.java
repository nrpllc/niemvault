package gov.niemplatform.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * {@code niem incident} at the command layer.
 *
 * <p>What the command owns: its arguments, its refusals, and its exit codes. Reading the graph is
 * covered against a real Neo4j in {@code Neo4jIncidentReaderTest}, so nothing here needs Docker —
 * which matters, because a CLI whose argument handling can only be tested with a container is a CLI
 * whose argument handling stops being tested.
 *
 * <p>Exit codes get the attention. A command that prints a problem and exits zero is worse than one
 * that says nothing, because the scheduled job wrapping it reports success.
 */
@DisplayName("niem incident")
class IncidentCommandTest {

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

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("refuses to run without the password in the environment")
    void requiresThePasswordFromTheEnvironment() {
        // Exit 2, and before any connection is attempted. The password is deliberately not an
        // option: one on a command line is in the shell history and the process list.
        assertThat(run("incident", "--neo4j-uri", "bolt://localhost:7687")).isEqualTo(2);
        assertThat(stderr()).contains("NIEM_NEO4J_PASSWORD");
    }

    @Test
    @DisplayName("requires the graph it is supposed to read")
    void requiresTheUri() {
        assertThat(run("incident")).isEqualTo(2);
        assertThat(stderr()).contains("--neo4j-uri");
    }

    @Test
    @DisplayName("is registered as a subcommand")
    void isRegistered() {
        // Asserted against the command model rather than captured --help output. picocli binds its
        // writers when the CommandLine is constructed, so usage text escapes a System.out redirect
        // installed afterwards, and an assertion on it passes or fails for the wrong reason.
        assertThat(new CommandLine(new NiemCli()).getSubcommands()).containsKey("incident");
    }

    @Test
    @DisplayName("takes the incident number as the argument an operator actually has")
    void takesTheIncidentNumber() {
        // The agency's own reference, not the canonical identity: the number is on the CAD export,
        // on the paperwork, and in the phone call. The identity is on the way out, not the way in.
        CommandLine incident = new CommandLine(new NiemCli()).getSubcommands().get("incident");

        assertThat(incident.getCommandSpec().positionalParameters()).singleElement()
                .satisfies(parameter -> {
                    assertThat(parameter.paramLabel()).contains("incidentNumber");
                    // Optional: with no number the command lists what is in the graph, which is the
                    // first question when nothing is known yet.
                    assertThat(parameter.arity().min()).isZero();
                });
    }

    @Test
    @DisplayName("is named for what the model holds, and there is no `niem case`")
    void isNotCalledCase() {
        // The canonical model has Incident and no Case. A case aggregates incidents and carries a
        // lifecycle; naming this `case` would promise an aggregation that does not exist.
        assertThat(new CommandLine(new NiemCli()).getSubcommands()).doesNotContainKey("case");
    }
}
