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
 * The operator CLI, exercised the way an operator and a scheduled job use it.
 *
 * <p>Exit codes get as much attention as output here. A CLI that prints a problem and exits zero
 * is worse than one that says nothing, because the scheduled job that wraps it will report
 * success -- and a pipeline that silently stops running is the failure mode this whole platform is
 * built to prevent.
 */
class NiemCliTest {

    @TempDir
    Path work;

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
        // Colour off: picocli would otherwise wrap its usage text in ANSI escapes, and an
        // assertion on "Specify a command" would fail against "[31m[1mSpecify...".
        command.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));

        PrintStream systemOut = System.out;
        PrintStream systemErr = System.err;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            return command.execute(args);
        } finally {
            // picocli writes usage text with print(), not println(), so autoflush does not
            // guarantee the buffer is complete by the time the assertion reads it.
            outWriter.flush();
            errWriter.flush();
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

    /** Copies the law enforcement module's shipped artifacts into a module directory. */
    private Path moduleDirectory() throws IOException {
        Path module = Files.createDirectories(work.resolve("module"));
        Files.createDirectories(module.resolve("mappings"));
        Files.createDirectories(module.resolve("contracts"));

        // Every domain module ships a manifest declaring its compatibility range (spec section 7),
        // and validate refuses a module without one.
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

    private static void copyResource(String resource, Path target) throws IOException {
        try (InputStream stream = NiemCliTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new AssertionError("missing test resource: " + resource);
            }
            Files.copy(stream, target);
        }
    }

    private Path dropWith(String fixture) throws IOException {
        Path drop = Files.createDirectories(work.resolve("drop"));
        copyResource("/fixtures/" + fixture, drop.resolve(fixture));
        return drop;
    }

    @Test
    @DisplayName("no subcommand is an error, not a silent success")
    void noSubcommandFails() {
        assertThat(run()).isNotZero();
        assertThat(stderr()).contains("Specify a command");
    }

    @Nested
    @DisplayName("validate")
    class Validate {

        @Test
        @DisplayName("coherent content exits zero and describes the mapping")
        void coherentContent() throws IOException {
            int exit = run("validate", "--module", moduleDirectory().toString());

            assertThat(exit).isZero();
            assertThat(stdout())
                    .contains("cad-to-canonical@1.0.0")
                    .contains("map-incident")
                    .contains("identity resolved by bundled-deterministic")
                    .contains("OK: 1 mapping(s)");
        }

        @Test
        @DisplayName("a hop pinning a contract version that is not present is reported")
        void versionMismatchReported() throws IOException {
            Path module = moduleDirectory();
            Path mapping = module.resolve("mappings").resolve("cad-to-canonical-1.0.0.yaml");
            Files.writeString(mapping, Files.readString(mapping)
                    .replace("contract: cad-person-to-canonical\n    version: \"1.0.0\"",
                            "contract: cad-person-to-canonical\n    version: \"2.0.0\""));

            int exit = run("validate", "--module", module.toString());

            assertThat(exit).isEqualTo(1);
            assertThat(stderr()).contains("pins contract version 2.0.0");
        }

        @Test
        @DisplayName("a malformed mapping is reported rather than thrown at the operator")
        void malformedMappingReported() throws IOException {
            Path module = moduleDirectory();
            Path mapping = module.resolve("mappings").resolve("cad-to-canonical-1.0.0.yaml");
            Files.writeString(mapping, Files.readString(mapping).replace("type: copy", "type: teleport"));

            int exit = run("validate", "--module", module.toString());

            assertThat(exit).isEqualTo(1);
            assertThat(stderr()).contains("teleport");
        }

        @Test
        @DisplayName("a module directory that does not exist fails cleanly")
        void missingModule() {
            assertThat(run("validate", "--module", work.resolve("nope").toString())).isEqualTo(1);
            assertThat(stderr()).contains("Not a directory");
        }
    }

    @Nested
    @DisplayName("run")
    class Run {

        @Test
        @DisplayName("a clean export exits zero and reports what it produced")
        void cleanExport() throws IOException {
            Path module = moduleDirectory();
            // DIRECT explicitly: only the in-process engine can report violation and cluster
            // counts, because on Flink those live inside the operator.
            int exit = run("run", "--tenant", "test.agency", "--engine", "DIRECT",
                    "--module", module.toString(),
                    "--mapping", module.resolve("mappings").resolve("cad-to-canonical-1.0.0.yaml").toString(),
                    "--drop", dropWith("incidents.csv").toString(),
                    "--bronze", work.resolve("bronze").toString(),
                    "--out", work.resolve("canonical.jsonl").toString());

            assertThat(exit).isZero();
            assertThat(stdout())
                    .contains("Landed 10 record(s)")
                    .contains("Mapped 30 canonical record(s)")
                    .contains("Resolved 6 person cluster(s)")
                    .contains("No contract violations");
        }

        @Test
        @DisplayName("refuses a half-configured silver store before landing anything")
        void refusesHalfConfiguredSilver() throws IOException {
            // Discovering this after a feed is ingested but before it could be stored leaves an
            // operator replaying to catch up. Recoverable only because bronze exists.
            Path module = moduleDirectory();
            int exit = run("run", "--tenant", "test.agency", "--engine", "DIRECT",
                    "--module", module.toString(),
                    "--mapping", module.resolve("mappings").resolve("cad-to-canonical-1.0.0.yaml").toString(),
                    "--drop", dropWith("incidents.csv").toString(),
                    "--bronze", work.resolve("bronze").toString(),
                    "--silver-warehouse", "s3://silver/warehouse");

            assertThat(exit).isEqualTo(1);
            assertThat(stderr()).contains("--silver-catalog-uri and --silver-warehouse are given together");
            assertThat(work.resolve("bronze")).as("nothing was landed").doesNotExist();
        }

        @Test
        @DisplayName("silver not being written is stated, not left to be inferred")
        void silverGapIsStated() throws IOException {
            Path module = moduleDirectory();
            run("run", "--tenant", "test.agency",
                    "--module", module.toString(),
                    "--mapping", module.resolve("mappings").resolve("cad-to-canonical-1.0.0.yaml").toString(),
                    "--drop", dropWith("incidents.csv").toString(),
                    "--bronze", work.resolve("bronze").toString());

            assertThat(stdout()).contains("Silver was not written");
        }

        @Test
        @DisplayName("quarantined records exit 2: not a failure, not a clean run either")
        void quarantineExitsTwo() throws IOException {
            Path module = moduleDirectory();
            int exit = run("run", "--tenant", "test.agency", "--engine", "DIRECT",
                    "--module", module.toString(),
                    "--mapping", module.resolve("mappings").resolve("cad-to-canonical-1.0.0.yaml").toString(),
                    "--drop", dropWith("incidents-drifted.csv").toString(),
                    "--bronze", work.resolve("bronze").toString(),
                    "--quarantine-out", work.resolve("quarantine.jsonl").toString());

            assertThat(exit)
                    .as("a scheduled job must be able to tell a clean run from a dirty one")
                    .isEqualTo(2);
            assertThat(stdout()).contains("contract violation(s)");
        }

        @Test
        @DisplayName("quarantine keeps the values the event redacts")
        void quarantineKeepsValues() throws IOException {
            Path module = moduleDirectory();
            Path quarantine = work.resolve("quarantine.jsonl");
            run("run", "--tenant", "test.agency", "--engine", "DIRECT",
                    "--module", module.toString(),
                    "--mapping", module.resolve("mappings").resolve("cad-to-canonical-1.0.0.yaml").toString(),
                    "--drop", dropWith("incidents-drifted.csv").toString(),
                    "--bronze", work.resolve("bronze").toString(),
                    "--quarantine-out", quarantine.toString());

            String held = Files.readString(quarantine);
            assertThat(held).contains("1979-12-01");
            assertThat(stdout())
                    .as("the same value must not appear in the event stream")
                    .doesNotContain("1979-12-01")
                    .contains("####-##-##");
        }

        @Test
        @DisplayName("the Flink engine reports only what the driver can actually know")
        void flinkEngineDoesNotClaimWhatItCannotSee() throws IOException {
            Path module = moduleDirectory();
            int exit = run("run", "--tenant", "test.agency", "--engine", "FLINK",
                    "--module", module.toString(),
                    "--mapping", module.resolve("mappings").resolve("cad-to-canonical-1.0.0.yaml").toString(),
                    "--drop", dropWith("incidents-drifted.csv").toString(),
                    "--bronze", work.resolve("bronze").toString());

            assertThat(stdout())
                    .as("printing 'No contract violations' from a driver that saw none would be "
                            + "a lie, since the recorder lives inside the operator")
                    .doesNotContain("No contract violations")
                    .contains("not visible from the driver");
            assertThat(exit)
                    .as("3 means ran, outcome not determinable here -- not a claimed clean run")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("a second run maps only what it landed, not all of bronze again")
        void secondRunMapsOnlyNewBatches() throws IOException {
            Path module = moduleDirectory();
            Path mapping = module.resolve("mappings").resolve("cad-to-canonical-1.0.0.yaml");
            Path bronze = work.resolve("bronze");

            Path dayOne = Files.createDirectories(work.resolve("day1"));
            copyResource("/fixtures/incidents.csv", dayOne.resolve("incidents.csv"));
            run("run", "--tenant", "test.agency", "--engine", "DIRECT", "--module", module.toString(),
                    "--mapping", mapping.toString(),
                    "--drop", dayOne.toString(), "--bronze", bronze.toString());
            assertThat(stdout()).contains("Mapped 30 canonical record(s)");

            Path dayTwo = Files.createDirectories(work.resolve("day2"));
            copyResource("/fixtures/incidents-drifted.csv", dayTwo.resolve("incidents-drifted.csv"));
            run("run", "--tenant", "test.agency", "--engine", "DIRECT", "--module", module.toString(),
                    "--mapping", mapping.toString(),
                    "--drop", dayTwo.toString(), "--bronze", bronze.toString());

            // Bronze is append-only and accumulates. Mapping all of it every run would re-emit
            // every record the platform has ever seen; re-mapping a range is replay's job.
            assertThat(stdout())
                    .contains("Landed 5 record(s)")
                    .contains("Mapped 8 canonical record(s)")
                    .doesNotContain("Mapped 38 canonical record(s)");
        }

        @Test
        @DisplayName("an empty drop directory maps nothing and exits zero")
        void emptyDropIsNotAnError() throws IOException {
            Path module = moduleDirectory();
            int exit = run("run", "--tenant", "test.agency", "--engine", "DIRECT",
                    "--module", module.toString(),
                    "--mapping", module.resolve("mappings").resolve("cad-to-canonical-1.0.0.yaml").toString(),
                    "--drop", Files.createDirectories(work.resolve("empty-drop")).toString(),
                    "--bronze", work.resolve("bronze").toString());

            assertThat(exit).isZero();
            assertThat(stdout()).contains("Nothing to map");
        }

        @Test
        @DisplayName("canonical output is one JSON object per record")
        void canonicalOutput() throws IOException {
            Path module = moduleDirectory();
            Path canonical = work.resolve("canonical.jsonl");
            run("run", "--tenant", "test.agency", "--engine", "DIRECT",
                    "--module", module.toString(),
                    "--mapping", module.resolve("mappings").resolve("cad-to-canonical-1.0.0.yaml").toString(),
                    "--drop", dropWith("incidents.csv").toString(),
                    "--bronze", work.resolve("bronze").toString(),
                    "--out", canonical.toString());

            List<String> lines = Files.readAllLines(canonical);
            assertThat(lines).hasSize(30);
            assertThat(lines).anySatisfy(line -> assertThat(line).contains("#Person"));
            assertThat(lines).anySatisfy(line ->
                    assertThat(line).contains("INC/RIVERTON-PD/2026-000114"));
        }
    }

    @Nested
    @DisplayName("inspect")
    class Inspect {

        @Test
        @DisplayName("reports batches and envelope metadata for what landed")
        void reportsBronze() throws IOException {
            Path module = moduleDirectory();
            Path bronze = work.resolve("bronze");
            run("run", "--tenant", "test.agency", "--engine", "DIRECT",
                    "--module", module.toString(),
                    "--mapping", module.resolve("mappings").resolve("cad-to-canonical-1.0.0.yaml").toString(),
                    "--drop", dropWith("incidents.csv").toString(),
                    "--bronze", bronze.toString());

            int exit = run("inspect", "--bronze", bronze.toString(), "--envelopes", "--limit", "2");

            assertThat(exit).isZero();
            assertThat(stdout())
                    .contains("source riverton-pd-cad")
                    .contains("10 record(s)")
                    .contains("incidents.csv#000002")
                    .contains("sha256:");
        }

        @Test
        @DisplayName("payloads are withheld unless asked for, because bronze holds raw source data")
        void payloadsWithheldByDefault() throws IOException {
            Path module = moduleDirectory();
            Path bronze = work.resolve("bronze");
            run("run", "--tenant", "test.agency", "--engine", "DIRECT",
                    "--module", module.toString(),
                    "--mapping", module.resolve("mappings").resolve("cad-to-canonical-1.0.0.yaml").toString(),
                    "--drop", dropWith("incidents.csv").toString(),
                    "--bronze", bronze.toString());

            run("inspect", "--bronze", bronze.toString(), "--envelopes");
            assertThat(stdout()).doesNotContain("DOE, JANE M");

            run("inspect", "--bronze", bronze.toString(), "--envelopes", "--payloads");
            assertThat(stdout()).contains("DOE, JANE M");
        }

        @Test
        @DisplayName("an empty bronze root is reported, not treated as an error")
        void emptyBronze() {
            assertThat(run("inspect", "--bronze", work.resolve("empty").toString())).isZero();
            assertThat(stdout()).contains("No sources have landed anything");
        }
    }

    /**
     * The guards in front of a destructive command.
     *
     * <p>Replay drops and rewrites silver. Everything here is about the operator finding out
     * before that happens rather than after. Rebuilding silver itself needs an object store and is
     * covered by the replay driver's own docker-tagged tests; what cannot be covered there is the
     * command refusing to start.
     */
    @Nested
    @DisplayName("replay")
    class Replay {

        private String[] baseArgs(Path module, String... extra) {
            List<String> args = new java.util.ArrayList<>(List.of("replay", "--tenant", "test.agency",
                    "--module", module.toString(),
                    "--mapping", module.resolve("mappings").resolve("cad-to-canonical-1.0.0.yaml").toString(),
                    "--bronze", work.resolve("bronze").toString(),
                    "--silver-catalog-uri", "jdbc:h2:mem:replay",
                    "--silver-warehouse", "s3://silver/warehouse"));
            args.addAll(List.of(extra));
            return args.toArray(String[]::new);
        }

        @Test
        @DisplayName("is offered at all, because §8 puts it in the Phase 1 operator CLI")
        void isRegistered() {
            // Asked of the command model rather than of --help output: this is about the command
            // existing, and routing it through picocli's usage renderer would test the renderer.
            assertThat(new CommandLine(new NiemCli()).getSubcommands())
                    .containsKeys("validate", "run", "replay", "inspect");
        }

        @Test
        @DisplayName("refuses a single batch combined with a range, rather than picking one")
        void refusesContradictoryRange() throws IOException {
            int exit = run(baseArgs(moduleDirectory(), "--batch", "b1", "--from", "b0"));

            assertThat(exit).isEqualTo(1);
            assertThat(stderr()).contains("cannot be combined with --from or --to");
        }

        @Test
        @DisplayName("refuses to start when a credential is expected in the environment and absent")
        void refusesWithoutTheGraphPassword() throws IOException {
            // The password is never a command-line option: an option is written to shell history
            // and is visible in the process list to every user on the machine.
            int exit = run(baseArgs(moduleDirectory(), "--neo4j-uri", "bolt://localhost:7687"));

            assertThat(exit).isEqualTo(1);
            assertThat(stderr()).contains(ReplayCommand.NEO4J_PASSWORD);
        }

        @Test
        @DisplayName("--dry-run names the tables it would overwrite, and writes nothing")
        void dryRunReportsWhatItWouldOverwrite() throws IOException {
            // The first test to reach the summary at all. Every other replay test asserts a
            // refusal, so the happy path's own output was never executed -- and it carried a
            // printf that formatted a String with %d, which threw on every real run before
            // anything was written. A command whose successful path no test walks is a command
            // that is only tested at failing.
            int exit = run(baseArgs(moduleDirectory(), "--dry-run"));

            assertThat(exit).isZero();
            assertThat(stdout())
                    .contains("Silver tables this replay will drop and rewrite:")
                    .contains("Incident")
                    .contains("Person")
                    .contains("--dry-run: nothing was written.");
        }

        @Test
        @DisplayName("refuses incoherent content before touching the store it would overwrite")
        void refusesIncoherentContent() throws IOException {
            Path module = moduleDirectory();
            Path mapping = module.resolve("mappings").resolve("cad-to-canonical-1.0.0.yaml");
            Files.writeString(mapping, Files.readString(mapping)
                    .replace("contract: cad-person-to-canonical\n    version: \"1.0.0\"",
                            "contract: cad-person-to-canonical\n    version: \"2.0.0\""));

            int exit = run("replay", "--tenant", "test.agency",
                    "--module", module.toString(),
                    "--mapping", module.resolve("mappings").resolve("cad-to-canonical-1.0.0.yaml").toString(),
                    "--bronze", work.resolve("bronze").toString(),
                    "--silver-catalog-uri", "jdbc:h2:mem:replay",
                    "--silver-warehouse", "s3://silver/warehouse");

            assertThat(exit).isEqualTo(1);
            assertThat(stderr()).contains("Content is not coherent");
        }
    }
}
