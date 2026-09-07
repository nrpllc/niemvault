package gov.niemplatform.runtime.engine;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A complete bronze-to-silver mapping, as loaded from an artifact on disk (spec §5).
 *
 * <p>Mappings are data. This type is the in-memory form of a YAML artifact, compiled into a Flink
 * job graph at deploy time -- an agency changing a mapping edits the artifact and redeploys the
 * definition, never rebuilds the platform.
 *
 * <p>The definition is versioned independently of the platform (spec §7), which is why
 * {@link #version()} sits alongside the platform version rather than being derived from it.
 *
 * @param name mapping name, stable across versions
 * @param version content version of this mapping, semver
 * @param sourceId configured source this mapping reads
 * @param decoder how a landed payload becomes a source-shaped record
 * @param hops the mapping graph, in declaration order
 */
public record MappingDefinition(
        String name,
        String version,
        String sourceId,
        DecoderSpec decoder,
        List<HopDefinition> hops) implements Serializable {

    public MappingDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(decoder, "decoder");
        hops = List.copyOf(hops);

        if (!version.matches("\\d+\\.\\d+\\.\\d+")) {
            throw new IllegalArgumentException(
                    "A mapping version must be semver, found '" + version + "'");
        }
        if (hops.isEmpty()) {
            throw new IllegalArgumentException("A mapping must declare at least one hop");
        }
        validateGraph(name, hops);
    }

    /**
     * Checks the hop graph is a DAG with resolvable dependencies.
     *
     * <p>Done at load time rather than on the first record. A mapping with a cycle or a dangling
     * dependency is a deployment problem, and spec §9 wants those loud and early.
     */
    private static void validateGraph(String mappingName, List<HopDefinition> hops) {
        Map<String, HopDefinition> byId = new LinkedHashMap<>();
        for (HopDefinition hop : hops) {
            if (byId.put(hop.hopId(), hop) != null) {
                throw new IllegalArgumentException(
                        "Mapping '%s' declares hop '%s' twice".formatted(mappingName, hop.hopId()));
            }
        }
        for (HopDefinition hop : hops) {
            for (String dependency : hop.dependsOn()) {
                if (!byId.containsKey(dependency)) {
                    throw new IllegalArgumentException(
                            "Mapping '%s' hop '%s' depends on unknown hop '%s'"
                                    .formatted(mappingName, hop.hopId(), dependency));
                }
            }
        }
        detectCycles(mappingName, byId);
    }

    private static void detectCycles(String mappingName, Map<String, HopDefinition> byId) {
        Set<String> settled = new HashSet<>();
        for (String hopId : byId.keySet()) {
            walk(mappingName, byId, hopId, new LinkedHashSet<>(), settled);
        }
    }

    private static void walk(String mappingName, Map<String, HopDefinition> byId, String hopId,
            Set<String> onPath, Set<String> settled) {
        if (settled.contains(hopId)) {
            return;
        }
        if (!onPath.add(hopId)) {
            List<String> cycle = new ArrayList<>(onPath);
            cycle.add(hopId);
            throw new IllegalArgumentException(
                    "Mapping '%s' has a cycle in its hop graph: %s"
                            .formatted(mappingName, String.join(" -> ", cycle)));
        }
        for (String dependency : byId.get(hopId).dependsOn()) {
            walk(mappingName, byId, dependency, onPath, settled);
        }
        onPath.remove(hopId);
        settled.add(hopId);
    }

    /**
     * Hops in dependency order: every hop appears after the hops it reads from.
     *
     * <p>Stable for a given definition -- ties are broken by declaration order rather than by hash
     * order -- because acceptance criteria 6 and 7 both compare outputs, and an execution order
     * that varied between runs would make identical mappings produce differently ordered results.
     */
    public List<HopDefinition> hopsInDependencyOrder() {
        Map<String, HopDefinition> byId = new LinkedHashMap<>();
        hops.forEach(hop -> byId.put(hop.hopId(), hop));

        List<HopDefinition> ordered = new ArrayList<>(hops.size());
        Set<String> placed = new LinkedHashSet<>();
        for (HopDefinition hop : hops) {
            place(byId, hop, ordered, placed);
        }
        return List.copyOf(ordered);
    }

    private static void place(Map<String, HopDefinition> byId, HopDefinition hop,
            List<HopDefinition> ordered, Set<String> placed) {
        if (placed.contains(hop.hopId())) {
            return;
        }
        for (String dependency : hop.dependsOn()) {
            place(byId, byId.get(dependency), ordered, placed);
        }
        placed.add(hop.hopId());
        ordered.add(hop);
    }

    /** Identifier carried on lineage and events: {@code name@version}. */
    public String qualifiedName() {
        return name + "@" + version;
    }
}
