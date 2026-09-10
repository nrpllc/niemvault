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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * {@code run} reads whatever transport a source declares, not the one the command was written for.
 *
 * <p>The command used to construct a {@code FileDropConnector} itself, which meant adding a
 * transport meant editing the command -- and the next one after that, and the one after that. A
 * source is now an artifact naming its transport, and the connector registry resolves it (§4.3).
 *
 * <p>The assertion that matters is that both routes reach the same place: the shorthand and the
 * definition file produce identical landings, so a source moving from a nightly drop to a live topic
 * changes one file and nothing else.
 */
class RunAcceptsAnySourceTest {

    @TempDir
    Path work;

    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;

    private int run(String... args) {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        PrintStream systemOut = System.out;
        PrintStream systemErr = System.err;
        CommandLine command = new CommandLine(new NiemCli());
        command.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            return command.execute(args);
        } finally {
            System.out.flush();
            System.err.flush();
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
        try (InputStream stream = RunAcceptsAnySourceTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("missing test resource " + resource);
            }
            Files.copy(stream, destination);
        }
    }

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

    private Path dropDirectory() throws IOException {
        Path drop = Files.createDirectories(work.resolve("drop"));
        copyResource("/fixtures/incidents.csv", drop.resolve("incidents.csv"));
        return drop;
    }

    private String[] baseArgs(Path module, String bronze, String... sourceArgs) {
        List<String> args = new java.util.ArrayList<>(List.of(
                "run",
                "--module", module.toString(),
                "--tenant", "co.riverton.pd",
                "--mapping", module.resolve("mappings").resolve("cad-to-canonical-1.0.0.yaml").toString(),
                "--bronze", work.resolve(bronze).toString(),
                "--engine", "DIRECT"));
        args.addAll(List.of(sourceArgs));
        return args.toArray(String[]::new);
    }

    @Test
    @DisplayName("a file-drop source definition lands exactly what the --drop shorthand lands")
    void bothRoutesLandTheSameThing() throws IOException {
        Path module = moduleDirectory();
        Path drop = dropDirectory();

        Path definition = work.resolve("riverton-cad-file-drop.yaml");
        Files.writeString(definition, """
                sourceId: riverton-pd-cad
                connectorInstanceId: cad-file-drop-1
                type: file-drop
                settings:
                  directory: %s
                  filePattern: "*.csv"
                  skipHeaderLines: "1"
                """.formatted(drop.toString().replace("\\", "/")), StandardCharsets.UTF_8);

        int viaShorthand = run(baseArgs(module, "bronze-a", "--drop", drop.toString()));
        String shorthandOutput = stdout();

        int viaDefinition = run(baseArgs(module, "bronze-b", "--source", definition.toString()));
        String definitionOutput = stdout();

        assertThat(viaShorthand).isEqualTo(viaDefinition);
        assertThat(landedLineOf(definitionOutput)).isEqualTo(landedLineOf(shorthandOutput));
        assertThat(mappedLineOf(definitionOutput)).isEqualTo(mappedLineOf(shorthandOutput));
        // A source now says what it is before it says what it did, because retention and
        // interaction mode are facts an operator should not have to infer from behaviour.
        assertThat(definitionOutput).contains("over file-drop (POLL, RETAINED)");
    }

    @Test
    @DisplayName("a source definition for a transport this deployment cannot read says which it can")
    void namesTheTransportsItHas() throws IOException {
        Path module = moduleDirectory();
        Path definition = work.resolve("cdc.yaml");
        Files.writeString(definition, """
                sourceId: riverton-pd-cad
                connectorInstanceId: cdc-1
                type: cdc
                settings: {}
                """, StandardCharsets.UTF_8);

        int exit = run(baseArgs(module, "bronze-c", "--source", definition.toString()));

        assertThat(exit).isEqualTo(1);
        assertThat(stderr())
                .contains("no connector for transport 'cdc'")
                // The Kafka connector is on the classpath, so the registry found it. That is the
                // service-loader discovery §4.3 promises, observed rather than assumed.
                .contains("kafka");
    }

    @Test
    @DisplayName("a definition for a different source than the mapping is refused, not landed")
    void refusesASourceTheMappingWasNotWrittenFor() throws IOException {
        Path module = moduleDirectory();
        Path drop = dropDirectory();
        Path definition = work.resolve("other.yaml");
        Files.writeString(definition, """
                sourceId: some-other-agency-cad
                connectorInstanceId: cad-file-drop-1
                type: file-drop
                settings:
                  directory: %s
                """.formatted(drop.toString().replace("\\", "/")), StandardCharsets.UTF_8);

        int exit = run(baseArgs(module, "bronze-d", "--source", definition.toString()));

        // It would otherwise map perfectly and produce canonical records about the wrong feed. No
        // contract catches that, because every record is well-formed.
        assertThat(exit).isEqualTo(1);
        assertThat(stderr())
                .contains("declares source 'some-other-agency-cad'")
                .contains("riverton-pd-cad");
    }

    @Test
    @DisplayName("naming neither a drop nor a source is refused, rather than defaulting to one")
    void requiresASource() throws IOException {
        Path module = moduleDirectory();

        int exit = run(baseArgs(module, "bronze-e"));

        assertThat(exit).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(stderr()).contains("--drop");
    }

    private static String landedLineOf(String output) {
        return lineContaining(output, "Landed ");
    }

    private static String mappedLineOf(String output) {
        return lineContaining(output, "Mapped ");
    }

    private static String lineContaining(String output, String needle) {
        return output.lines().filter(line -> line.contains(needle)).findFirst().orElse("<absent>");
    }
}
