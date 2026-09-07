package gov.niemplatform.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * The operator CLI (spec §8).
 *
 * <p>Phase 1 scope lists four commands: {@code validate}, {@code run}, {@code replay}, and
 * {@code inspect}. Three are here. {@code replay} is deliberately absent rather than present and
 * failing: spec §5 defines replay as rebuilding <em>silver</em> from a bronze range and a mapping
 * version, and the silver store is not built yet (ADR 0005). A command that accepted the arguments
 * and then explained it could not do the job would be the placeholder spec §0 rule 4 forbids.
 *
 * <p>Everything the CLI does is offline and local. Spec §6 makes air-gapped delivery mandatory, so
 * no command may require outbound network access.
 */
@Command(
        name = "niem",
        mixinStandardHelpOptions = true,
        versionProvider = NiemCli.PlatformVersion.class,
        synopsisSubcommandLabel = "COMMAND",
        description = "Operator tooling for the NIEM integration platform.",
        subcommands = {
            ValidateCommand.class,
            DescribeCommand.class,
            AuthorCommand.class,
            RunCommand.class,
            ReplayCommand.class,
            InspectCommand.class,
            CatalogueCommand.class,
        })
public final class NiemCli implements java.util.concurrent.Callable<Integer> {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        // Invoked with no subcommand. Usage on stderr and a non-zero exit, so a mistyped command
        // in a scheduled job fails the job rather than looking like a successful no-op.
        //
        // Written to System.err rather than through picocli's writer. picocli re-instantiates the
        // command object through its factory during execute(), so a writer installed with setErr()
        // on the CommandLine the caller built is not the one this method would reach -- the output
        // escapes to the real stderr, and the behaviour cannot be asserted. System.err is what a
        // CLI should be writing usage to anyway.
        System.err.println("Specify a command.");
        spec.commandLine().usage(System.err);
        return CommandLine.ExitCode.USAGE;
    }

    /** Reports the platform version, which moves independently of content versions (spec §7). */
    static final class PlatformVersion implements CommandLine.IVersionProvider {

        @Override
        public String[] getVersion() {
            String version = NiemCli.class.getPackage().getImplementationVersion();
            return new String[] {
                "niem " + (version == null ? "(development build)" : version),
                "Platform version; canonical, mapping, and contract versions are declared per artifact.",
            };
        }
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new NiemCli())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute(args));
    }
}
