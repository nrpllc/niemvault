package gov.niemplatform.controlplane;

import gov.niemplatform.content.ModuleManifest;
import gov.niemplatform.content.ModuleManifestLoader;
import gov.niemplatform.content.SemanticVersion;
import gov.niemplatform.contracts.ContractLoadException;
import gov.niemplatform.runtime.engine.MappingDefinition;
import gov.niemplatform.runtime.engine.MappingLoadException;
import gov.niemplatform.runtime.engine.MappingLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A domain module open for authoring.
 *
 * <p>Reads and writes the module's real artifacts, and validates through the same loaders the
 * runtime uses (ADR 0021). Nothing here re-implements a check: a mapping the workspace accepts is a
 * mapping the pipeline will load, because it is literally the same code answering.
 *
 * <h2>Editing never overwrites a published mapping</h2>
 *
 * <p>Saving drafts the <em>next version</em> and leaves the original exactly as it was reviewed.
 * Mappings are versioned artifacts (§7) and changes must be attributable (§4.8), so an in-place
 * edit would quietly rewrite something an auditor may already have signed off. It also sidesteps a
 * practical problem: a mapping carries explanatory comments, and a YAML round trip strips them.
 * A new version is generated rather than round-tripped, so nothing is silently lost from the file
 * that still exists.
 */
public final class MappingWorkspace {

    private final Path moduleRoot;
    private final SemanticVersion platformVersion;

    public MappingWorkspace(Path moduleRoot, SemanticVersion platformVersion) {
        this.moduleRoot = Objects.requireNonNull(moduleRoot, "moduleRoot").toAbsolutePath().normalize();
        this.platformVersion = Objects.requireNonNull(platformVersion, "platformVersion");
    }

    public Path moduleRoot() {
        return moduleRoot;
    }

    /** The module's manifest, refused if this platform cannot run its content (§7). */
    public ModuleManifest manifest() {
        return new ModuleManifestLoader().loadFor(moduleRoot, platformVersion);
    }

    /** One mapping artifact on disk. */
    public record MappingFile(String fileName, String name, String version, boolean loadable) {

        public String qualifiedName() {
            return name + "@" + version;
        }
    }

    /**
     * Every mapping in the module, newest version first.
     *
     * <p>A mapping that fails to load is listed rather than hidden. Hiding it would leave an author
     * unable to open the very file they need to fix.
     */
    public List<MappingFile> mappings() {
        Path directory = moduleRoot.resolve("mappings");
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var stream = Files.list(directory)) {
            List<MappingFile> found = new ArrayList<>();
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .sorted()
                    .toList()) {
                found.add(describe(file));
            }
            found.sort(Comparator.comparing(MappingFile::name)
                    .thenComparing(MappingFile::version, Comparator.reverseOrder()));
            return List.copyOf(found);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list mappings under " + directory, e);
        }
    }

    private MappingFile describe(Path file) {
        String fileName = file.getFileName().toString();
        try {
            MappingDefinition definition = new MappingLoader().load(file);
            return new MappingFile(fileName, definition.name(), definition.version(), true);
        } catch (RuntimeException broken) {
            return new MappingFile(fileName, fileName.replace(".yaml", ""), "unknown", false);
        }
    }

    /** Loads one mapping for editing. */
    public MappingDefinition load(String fileName) {
        return new MappingLoader().load(mappingPath(fileName));
    }

    /** The raw YAML, for an author who would rather edit the text directly. */
    public String source(String fileName) {
        try {
            return Files.readString(mappingPath(fileName), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + fileName, e);
        }
    }

    /**
     * Validates candidate YAML without writing anything.
     *
     * <p>What the editor calls on every change. Returns the problems rather than throwing, because
     * a half-finished mapping is the normal state of one being edited and an exception per keystroke
     * would be useless.
     */
    public ValidationReport validate(String yaml) {
        List<String> problems = new ArrayList<>();
        MappingDefinition definition = null;
        try {
            definition = new MappingLoader().load(
                    new java.io.ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
                    "draft");
        } catch (MappingLoadException e) {
            e.problems().forEach(problem -> problems.add(problem.toString().trim()));
        } catch (RuntimeException e) {
            problems.add(String.valueOf(e.getMessage()));
        }

        if (definition != null) {
            problems.addAll(contractProblems(definition));
        }
        return new ValidationReport(definition, List.copyOf(problems));
    }

    /**
     * Cross-references a draft against the module's contracts.
     *
     * <p>A mapping is not valid on its own: every hop names a contract at a version, and a hop
     * pinning something the module does not carry would load happily here and fail at deploy.
     */
    private List<String> contractProblems(MappingDefinition definition) {
        List<String> problems = new ArrayList<>();
        try {
            var contracts = new gov.niemplatform.contracts.ContractLoader(
                    gov.niemplatform.canonical.meta.CanonicalTypeResolver.of(
                            gov.niemplatform.canonical.core.CoreCanonicalTypes.ALL))
                    .loadDirectory(moduleRoot.resolve("contracts"));

            definition.hops().forEach(hop -> {
                // Resolved by the name the hop writes down, not by hop id. Matching on hop id
                // would let a hop rename its contract to anything at all and still find the one
                // bound to it, which is precisely the typo an author needs caught.
                var byName = contracts.stream()
                        .filter(contract -> contract.id().name().equals(hop.contractName()))
                        .toList();
                if (byName.isEmpty()) {
                    problems.add("hop '%s' names contract '%s', which the module does not carry"
                            .formatted(hop.hopId(), hop.contractName()));
                    return;
                }

                var atVersion = byName.stream()
                        .filter(contract -> contract.id().version().equals(hop.contractVersion()))
                        .findFirst();
                if (atVersion.isEmpty()) {
                    problems.add("hop '%s' pins contract '%s' at %s; the module carries %s"
                            .formatted(hop.hopId(), hop.contractName(), hop.contractVersion(),
                                    byName.stream().map(contract -> contract.id().version()).toList()));
                    return;
                }

                if (!atVersion.get().hopId().equals(hop.hopId())) {
                    // The contract exists but guards a different hop. Deploying this would gate
                    // the hop against a schema written for someone else's data.
                    problems.add("contract '%s@%s' is written for hop '%s', not '%s'"
                            .formatted(hop.contractName(), hop.contractVersion(),
                                    atVersion.get().hopId(), hop.hopId()));
                }
            });

            // Identity is not enough. A mapping can name the right contract at the right version
            // and still fail to produce a field that contract requires, which loads cleanly and
            // then quarantines the entire feed at deploy.
            ContractCoverage.check(definition, byHop(contracts))
                    .forEach(gap -> problems.add(gap.message()));
        } catch (ContractLoadException e) {
            e.problems().forEach(problem -> problems.add(problem.toString().trim()));
        }
        return problems;
    }

    /** The module's contracts, keyed by the hop each one gates. */
    private static java.util.Map<String, gov.niemplatform.contracts.HopContract> byHop(
            List<gov.niemplatform.contracts.HopContract> contracts) {
        java.util.Map<String, gov.niemplatform.contracts.HopContract> byHop =
                new java.util.LinkedHashMap<>();
        contracts.forEach(contract -> byHop.put(contract.hopId(), contract));
        return byHop;
    }

    /** The contracts on disk, for anything that needs to cross-reference against them. */
    public List<gov.niemplatform.contracts.HopContract> contracts() {
        return new gov.niemplatform.contracts.ContractLoader(
                gov.niemplatform.canonical.meta.CanonicalTypeResolver.of(
                        gov.niemplatform.canonical.core.CoreCanonicalTypes.ALL))
                .loadDirectory(moduleRoot.resolve("contracts"));
    }

    /** The outcome of validating a draft. */
    public record ValidationReport(MappingDefinition definition, List<String> problems) {

        public boolean valid() {
            return problems.isEmpty() && definition != null;
        }
    }

    /**
     * Writes a draft as a new mapping version.
     *
     * @return the file written
     * @throws IllegalStateException if that version already exists, because silently replacing a
     *     reviewed artifact is the thing this method exists to avoid
     */
    public Path saveAsNewVersion(String yaml, String name, String version) {
        Path target = moduleRoot.resolve("mappings").resolve(name + "-" + version + ".yaml");
        if (Files.exists(target)) {
            throw new IllegalStateException(
                    target.getFileName() + " already exists; bump the version rather than "
                            + "replacing a mapping that may already have been reviewed");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, yaml, StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + target, e);
        }
    }

    /** Suggests the next patch version for a mapping, which is what most edits are. */
    public String nextVersion(String currentVersion) {
        SemanticVersion current = SemanticVersion.parse(currentVersion);
        return "%d.%d.%d".formatted(current.major(), current.minor(), current.patch() + 1);
    }

    private Path mappingPath(String fileName) {
        Path file = moduleRoot.resolve("mappings").resolve(fileName).normalize();
        // Refuse anything that escapes the module. The editor takes a file name from a browser.
        if (!file.startsWith(moduleRoot)) {
            throw new IllegalArgumentException("'" + fileName + "' is outside the module");
        }
        return file;
    }

    /** Whether a mapping of this name and version already exists. */
    public Optional<MappingFile> existing(String name, String version) {
        return mappings().stream()
                .filter(mapping -> mapping.name().equals(name) && mapping.version().equals(version))
                .findFirst();
    }
}
