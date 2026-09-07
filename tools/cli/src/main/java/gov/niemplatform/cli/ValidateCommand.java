package gov.niemplatform.cli;

import gov.niemplatform.content.ContentCompatibilityException;
import gov.niemplatform.content.ModuleManifest;
import gov.niemplatform.content.ModuleManifestLoader;
import gov.niemplatform.contracts.ContractLoadException;
import gov.niemplatform.runtime.engine.HopDefinition;
import gov.niemplatform.runtime.engine.MappingDefinition;
import gov.niemplatform.runtime.engine.MappingLoadException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Validates a domain module's content artifacts without running anything.
 *
 * <p>The command an operator runs before a change board meeting, and the one a deployment pipeline
 * runs as a gate. Everything it checks is checked again at load time when the pipeline starts --
 * that is the point of ADR 0010 -- but finding out at deploy time beats finding out when the
 * nightly load does not run.
 *
 * <p>Exit code 0 when the content is coherent, 1 when it is not, so it composes into a script.
 */
@Command(
        name = "validate",
        mixinStandardHelpOptions = true,
        description = "Validate a domain module's mappings and contracts.")
final class ValidateCommand implements Callable<Integer> {

    @Option(names = {"-m", "--module"}, required = true,
            description = "Domain module directory containing mappings/ and contracts/.")
    Path moduleDirectory;

    @Option(names = "--mapping",
            description = "Validate only this mapping artifact, rather than every one in the module.")
    Path singleMapping;

    @Override
    public Integer call() {
        // Normalised up front: the operator may pass a relative path, and relativizing an
        // absolute path against a relative one throws rather than producing something useful.
        Path module = moduleDirectory.toAbsolutePath().normalize();
        Path mappings = module.resolve("mappings");
        Path contracts = module.resolve("contracts");

        List<String> problems = new ArrayList<>();
        if (!Files.isDirectory(module)) {
            System.err.println("Not a directory: " + module);
            return 1;
        }

        // Compatibility first, before any artifact is read. Loading a mapping and only then
        // discovering the module does not support this platform would leave the operator guessing
        // which of the two was at fault (spec section 7).
        try {
            ModuleManifest manifest =
                    new ModuleManifestLoader().loadFor(module, PlatformVersion.running());
            System.out.printf("%s  %s%n", manifest.qualifiedName(), manifest.displayName());
            System.out.printf("  platform %s, running %s%n",
                    manifest.platformVersions(), PlatformVersion.running());
            System.out.printf("  canonical model %s%s%n%n",
                    manifest.canonicalModelVersion(),
                    manifest.steward() == null ? "" : ", steward " + manifest.steward());
        } catch (ContentCompatibilityException e) {
            e.problems().forEach(problem -> System.err.println("  " + problem));
            return 1;
        }
        if (!Files.isDirectory(contracts)) {
            problems.add("no contracts/ directory under " + module);
        }

        List<Path> mappingFiles = singleMapping != null
                ? List.of(singleMapping.toAbsolutePath().normalize())
                : ArtifactSet.yamlFiles(mappings);

        if (mappingFiles.isEmpty()) {
            problems.add("no mapping artifacts found under " + mappings);
        }

        int validated = 0;
        for (Path mappingFile : mappingFiles) {
            System.out.println("Checking " + module.relativize(mappingFile));
            try {
                ArtifactSet artifacts = ArtifactSet.load(mappingFile, contracts);
                describe(artifacts.mapping());

                List<String> crossReference = artifacts.crossReferenceProblems();
                crossReference.forEach(problem ->
                        problems.add(mappingFile.getFileName() + ": " + problem));
                if (crossReference.isEmpty()) {
                    validated++;
                }
            } catch (MappingLoadException e) {
                e.problems().forEach(problem -> problems.add(problem.toString().trim()));
            } catch (ContractLoadException e) {
                e.problems().forEach(problem -> problems.add(problem.toString().trim()));
            } catch (RuntimeException e) {
                problems.add(mappingFile.getFileName() + ": " + e.getMessage());
            }
        }

        System.out.println();
        if (problems.isEmpty()) {
            System.out.printf("OK: %d mapping(s) and their contracts are coherent.%n", validated);
            return 0;
        }
        System.err.printf("%d problem(s):%n", problems.size());
        problems.forEach(problem -> System.err.println("  " + problem));
        return 1;
    }

    /** Prints the shape of a mapping, so an operator can see what they are about to deploy. */
    private void describe(MappingDefinition mapping) {
        System.out.printf("  %s  source=%s  decoder=%s (%d column(s))%n",
                mapping.qualifiedName(),
                mapping.sourceId(),
                mapping.decoder().format(),
                mapping.decoder().columns().size());

        for (HopDefinition hop : mapping.hopsInDependencyOrder()) {
            String identity = switch (hop.identity().mode()) {
                case RESOLVE -> "resolved by " + hop.identity().providerId();
                case DERIVE -> "derived from " + hop.identity().deriveFrom();
            };
            System.out.printf("    hop %-22s -> %-28s %d step(s), identity %s%s%n",
                    hop.hopId(),
                    hop.identity().entityType(),
                    hop.steps().size(),
                    identity,
                    hop.dependsOn().isEmpty() ? "" : ", after " + hop.dependsOn());
        }
    }
}
