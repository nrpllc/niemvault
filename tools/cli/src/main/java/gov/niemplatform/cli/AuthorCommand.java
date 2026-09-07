package gov.niemplatform.cli;

import gov.niemplatform.controlplane.ControlPlaneServer;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Opens the mapping authoring surface (ADR 0021).
 *
 * <p>Runs until interrupted, because it is a tool someone works in rather than a job that
 * completes. Binds to loopback only: Phase 1 has no authentication beyond a stub (spec §8), so an
 * authoring surface must not be reachable from the network.
 */
@Command(
        name = "author",
        mixinStandardHelpOptions = true,
        description = "Open the mapping authoring surface in a browser.")
final class AuthorCommand implements Callable<Integer> {

    @Option(names = {"-m", "--module"}, required = true,
            description = "Domain module directory containing mappings/ and contracts/.")
    Path moduleDirectory;

    @Option(names = "--port", defaultValue = "8088",
            description = "Port to serve on. Default: ${DEFAULT-VALUE}")
    int port;

    @Option(names = "--bronze",
            description = "Bronze root. Lets suggestions read the shape of columns that have landed "
                    + "-- never their values (ADR 0023). Omitted: suggestions from names alone.")
    Path bronzeRoot;

    @Override
    public Integer call() throws Exception {
        try (ControlPlaneServer server = ControlPlaneServer.open(
                moduleDirectory.toAbsolutePath().normalize(), PlatformVersion.running(), port)) {

            if (bronzeRoot != null) {
                server.profilingFrom(bronzeRoot.toAbsolutePath().normalize());
            }

            System.out.printf("Mapping authoring: %s%n", server.url());
            System.out.printf("  module  %s%n", moduleDirectory.toAbsolutePath().normalize());
            System.out.println(bronzeRoot == null
                    ? "  shapes  not read -- pass --bronze to let suggestions see column shapes"
                    : "  shapes  read from " + bronzeRoot.toAbsolutePath().normalize()
                            + " (shapes only, never values)");
            System.out.println("  Saving writes a new mapping version; the file you opened is left as it was.");
            System.out.println("  Press Ctrl+C to stop.");

            CountDownLatch until = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(until::countDown));
            until.await();
        }
        return 0;
    }
}
