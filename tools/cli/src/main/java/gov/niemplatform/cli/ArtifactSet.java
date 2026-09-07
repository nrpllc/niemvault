package gov.niemplatform.cli;

import gov.niemplatform.canonical.core.CoreCanonicalTypes;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.canonical.meta.CanonicalTypeResolver;
import gov.niemplatform.contracts.ContractLoader;
import gov.niemplatform.contracts.HopContract;
import gov.niemplatform.runtime.engine.MappingDefinition;
import gov.niemplatform.runtime.engine.MappingLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The content artifacts a run needs, loaded from a domain module directory.
 *
 * <p>A module lays its content out as spec §3 describes:
 *
 * <pre>
 *   &lt;module&gt;/mappings/*.yaml
 *   &lt;module&gt;/contracts/*.yaml
 * </pre>
 *
 * <p>Loading them together matters: a mapping names the contracts its hops expect, and a contract
 * names a canonical type. Nothing checks those references until all three are in the same room,
 * which is why {@code validate} loads the set rather than each file alone.
 */
record ArtifactSet(
        MappingDefinition mapping,
        Map<String, HopContract> contractsByHop,
        Map<String, CanonicalTypeDescriptor> canonicalTypes) {

    /** Where the platform's canonical types come from. Phase 1 ships exactly the core module. */
    static CanonicalTypeResolver canonicalTypeResolver() {
        return CanonicalTypeResolver.of(CoreCanonicalTypes.ALL);
    }

    static Map<String, CanonicalTypeDescriptor> allCanonicalTypes() {
        Map<String, CanonicalTypeDescriptor> types = new LinkedHashMap<>();
        CoreCanonicalTypes.ALL.forEach(type -> types.put(type.name(), type));
        return types;
    }

    /**
     * Loads one mapping and every contract beside it.
     *
     * @param mappingFile the mapping artifact
     * @param contractsDirectory directory of contract artifacts
     */
    static ArtifactSet load(Path mappingFile, Path contractsDirectory) {
        MappingDefinition mapping = new MappingLoader().load(mappingFile);
        List<HopContract> contracts =
                new ContractLoader(canonicalTypeResolver()).loadDirectory(contractsDirectory);

        Map<String, HopContract> byHop = new LinkedHashMap<>();
        contracts.forEach(contract -> byHop.put(contract.hopId(), contract));
        return new ArtifactSet(mapping, byHop, allCanonicalTypes());
    }

    /**
     * Checks every hop has the contract it names, at the version it names.
     *
     * <p>A hop pinning {@code cad-person-to-canonical@1.0.0} against a directory holding only
     * 1.1.0 is a deployment that would run with expectations nobody wrote. Reported rather than
     * resolved: picking "the closest version" is how content versions stop meaning anything.
     *
     * @return one line per problem, empty when the set is coherent
     */
    List<String> crossReferenceProblems() {
        List<String> problems = new ArrayList<>();
        mapping.hops().forEach(hop -> {
            HopContract contract = contractsByHop.get(hop.hopId());
            if (contract == null) {
                problems.add("hop '%s' names contract '%s@%s', which is not present"
                        .formatted(hop.hopId(), hop.contractName(), hop.contractVersion()));
                return;
            }
            if (!contract.id().name().equals(hop.contractName())) {
                problems.add("hop '%s' names contract '%s' but the contract for that hop is '%s'"
                        .formatted(hop.hopId(), hop.contractName(), contract.id().name()));
            }
            if (!contract.id().version().equals(hop.contractVersion())) {
                problems.add("hop '%s' pins contract version %s but %s is present"
                        .formatted(hop.hopId(), hop.contractVersion(), contract.id().version()));
            }
        });

        contractsByHop.forEach((hopId, contract) -> {
            if (mapping.hops().stream().noneMatch(hop -> hop.hopId().equals(hopId))) {
                problems.add("contract '%s' governs hop '%s', which the mapping does not declare"
                        .formatted(contract.id(), hopId));
            }
        });
        return problems;
    }

    /** Every {@code *.yaml} directly under a directory, sorted. */
    static List<Path> yamlFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + directory, e);
        }
    }
}
