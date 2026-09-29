package gov.niemplatform.cli;

import gov.niemplatform.connectors.api.ConnectorConfigurationException;
import gov.niemplatform.connectors.api.ConnectorRegistry;
import gov.niemplatform.connectors.api.SourceCheckpointStore;
import gov.niemplatform.connectors.api.SourceConnector;
import gov.niemplatform.connectors.api.SourceDefinition;
import gov.niemplatform.connectors.api.SourceDefinitionException;
import gov.niemplatform.content.ContentCompatibilityException;
import gov.niemplatform.content.ModuleManifest;
import gov.niemplatform.content.ModuleManifestLoader;
import gov.niemplatform.contracts.ContractLoadException;
import gov.niemplatform.runtime.engine.HopDefinition;
import gov.niemplatform.runtime.engine.MappingDefinition;
import gov.niemplatform.runtime.engine.MappingLoadException;
import gov.niemplatform.runtime.engine.MappingLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
        description = "Validate a domain module's mappings, contracts, and source definitions.")
final class ValidateCommand implements Callable<Integer> {

    @Option(names = {"-m", "--module"}, required = true,
            description = "Domain module directory containing mappings/ and contracts/.")
    Path moduleDirectory;

    @Option(names = "--mapping",
            description = "Validate only this mapping artifact, rather than every one in the module.")
    Path singleMapping;

    @Option(names = "--source",
            description = "Validate only this source definition, rather than every one in the module.")
    Path singleSource;

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

        // Claimed by every mapping the module ships, never only by the ones being validated in this
        // invocation. Narrowing with --mapping would otherwise shrink the set and make a source
        // definition that is perfectly well paired look like one nothing maps.
        Set<String> claimedSourceIds = claimedSourceIds(mappings);

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

        int transports = checkSourceDefinitions(module, claimedSourceIds, problems);
        int pipelines = singleMapping == null && singleSource == null
                ? checkPipelines(module, problems) : 0;

        System.out.println();
        if (problems.isEmpty()) {
            System.out.printf("OK: %d mapping(s) and their contracts are coherent, "
                    + "%d transport(s) configured, %d pipeline(s) resolve.%n",
                    validated, transports, pipelines);
            return 0;
        }
        System.err.printf("%d problem(s):%n", problems.size());
        problems.forEach(problem -> System.err.println("  " + problem));
        return 1;
    }

    /**
     * Every pipeline in the module resolves (ADR 0037).
     *
     * <p>At authoring strictness: a projection or exchange the module does not ship is a note, not a
     * problem, because naming a deployment's database is exactly what a module cannot do. Everything
     * the module does ship -- the origin, the mapping, the exchanges it carries -- must resolve and fit.
     */
    private int checkPipelines(Path module, List<String> problems) {
        var catalog = gov.niemplatform.pipeline.ArtifactCatalog.of(module, List.of());
        var resolver = new gov.niemplatform.pipeline.PipelineResolver(catalog,
                gov.niemplatform.connectors.api.ConnectorRegistry.discover(),
                gov.niemplatform.projections.api.ProjectionRegistry.discover(),
                gov.niemplatform.exchange.api.ExchangeRegistry.discover());
        int resolved = 0;
        for (Path file : ArtifactSet.yamlFiles(module.resolve("pipelines"))) {
            System.out.println("Checking " + module.relativize(file));
            try {
                var pipeline = gov.niemplatform.pipeline.PipelineDefinition.load(file);
                var resolution = resolver.resolve(pipeline,
                        gov.niemplatform.pipeline.PipelineResolver.Strictness.AUTHORING);
                System.out.printf("  %s  origin=%s/%s  mapping=%s  destinations=%d%n",
                        pipeline.qualifiedName(), pipeline.originSource(), pipeline.originInstance(),
                        pipeline.mapping(), pipeline.projections().size() + pipeline.exchanges().size());
                resolution.notes().forEach(note -> System.out.println("  note: " + note));
                resolution.problems().forEach(problem ->
                        problems.add(file.getFileName() + ": " + problem));
                if (resolution.runnable()) {
                    resolved++;
                }
            } catch (gov.niemplatform.pipeline.PipelineDefinitionException e) {
                e.problems().forEach(problem -> problems.add(file.getFileName() + ": " + problem));
            }
        }
        return resolved;
    }

    /**
     * Every source id the module's mappings claim.
     *
     * <p>Read leniently: a mapping that cannot be loaded is already being reported as a problem by
     * the loop above, or was deliberately excluded from this invocation by {@code --mapping}.
     * Either way, failing again here would turn one fault into two lines.
     */
    private Set<String> claimedSourceIds(Path mappings) {
        Set<String> claimed = new LinkedHashSet<>();
        for (Path mappingFile : ArtifactSet.yamlFiles(mappings)) {
            try {
                claimed.add(new MappingLoader().load(mappingFile).sourceId());
            } catch (RuntimeException alreadyReportedOrOutOfScope) {
                // Deliberately silent. See the javadoc.
            }
        }
        return claimed;
    }

    /**
     * Checks that each source definition names a transport this deployment has, and that the
     * transport accepts its settings (spec §4.3, ADR 0029).
     *
     * <p>Well-formedness only, which is the same boundary {@code configure} draws against
     * {@code health}: whether the broker answers or the export directory exists is a question about
     * an environment, and a records manager reviewing a definition on a laptop stands in none of
     * them. {@code run} asks {@code health()} before it lands anything, so reachability is still
     * found before a batch is half-committed.
     *
     * <p>The check is the connector's own {@code configure}, never a second validator written here
     * (ADR 0020/0021). That is what makes a definition this command accepts a definition the
     * pipeline will load.
     *
     * <p>A module with no {@code sources/} directory has no transports to check, and that is not a
     * fault: transport configuration may equally live in a deployment's own repository, because the
     * agency running a feed is not always the one that authored the mapping.
     *
     * @return how many definitions were checked and found usable
     */
    private int checkSourceDefinitions(Path module, Set<String> claimedSourceIds, List<String> problems) {
        List<Path> sourceFiles = singleSource != null
                ? List.of(singleSource.toAbsolutePath().normalize())
                : ArtifactSet.yamlFiles(module.resolve("sources"));

        if (sourceFiles.isEmpty()) {
            return 0;
        }

        // Discovered once for the whole pass. A definition naming a transport this deployment does
        // not have is reported with the list of what it does have: an air-gapped operator has to be
        // able to tell a missing jar from a misspelled transport, and nothing else distinguishes
        // them.
        ConnectorRegistry registry = ConnectorRegistry.discover();

        int usable = 0;
        for (Path sourceFile : sourceFiles) {
            String name = sourceFile.startsWith(module)
                    ? module.relativize(sourceFile).toString()
                    : sourceFile.getFileName().toString();
            System.out.println("Checking " + name);
            try {
                SourceDefinition definition = SourceDefinition.load(sourceFile);

                // A usable store rather than the unavailable one, because whether this deployment
                // configured a checkpoint directory is a property of the run (niem run
                // --checkpoints), not of the artifact being reviewed. Refusing an SFTP definition
                // here would report a deployment's missing flag as a fault in a file that is fine.
                SourceConnector connector = definition.connectorFrom(
                        registry, SourceCheckpointStore.inMemory());

                System.out.printf("  %s  source=%s  transport=%s (%s, %s)%s%n",
                        definition.connectorInstanceId(),
                        definition.sourceId(),
                        definition.type(),
                        connector.interactionMode(),
                        connector.retention(),
                        definition.declaredFreshnessSla()
                                .map(sla -> ", freshness " + sla).orElse(""));

                usable++;
                if (!claimedSourceIds.isEmpty() && !claimedSourceIds.contains(definition.sourceId())) {
                    // Said, and deliberately not fatal. The definition is correct -- it configures a
                    // transport, and a transport is all it is allowed to describe -- so there is
                    // nothing here to fix by editing this file. What it means is that the source has
                    // been described and not yet mapped, which is a real state to be in: riverton-
                    // rms-cdc is shipped for exactly that reason (ADR 0032), to record that a change
                    // feed needs no connector of its own, before anyone has written the mapping that
                    // reads it.
                    //
                    // Failing would make a module unable to describe a transport ahead of its
                    // mapping, which is the order onboarding actually happens in: the agency
                    // configures the feed first and the steward maps it afterwards.
                    System.out.printf("  note: no mapping in this module reads '%s'. Nothing will "
                            + "land from it until one does.%n", definition.sourceId());
                }
            } catch (SourceDefinitionException e) {
                e.problems().forEach(problem -> problems.add(name + ": " + problem));
            } catch (ConnectorConfigurationException e) {
                e.problems().forEach(problem -> problems.add(name + ": " + problem));
            } catch (RuntimeException e) {
                problems.add(name + ": " + e.getMessage());
            }
        }
        return usable;
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
