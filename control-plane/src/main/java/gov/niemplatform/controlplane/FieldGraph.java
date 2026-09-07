package gov.niemplatform.controlplane;

import gov.niemplatform.runtime.engine.HopDefinition;
import gov.niemplatform.runtime.engine.IdentitySpec;
import gov.niemplatform.runtime.engine.MappingDefinition;
import gov.niemplatform.runtime.transforms.TransformSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One hop drawn as the data flow it actually is: source columns in, transforms, fields out.
 *
 * <p>This is the graph a person authoring a mapping is holding in their head. The hop-level picture
 * says a hop exists; this says <em>NAME_FULL is split on a comma, uppercased, and becomes
 * surName</em>. Nobody can review a mapping from the first picture, and nobody should have to read
 * YAML to get the second.
 *
 * <h2>Why a target can appear more than once</h2>
 *
 * <p>Steps assign in order, and a later step may read what an earlier one wrote — {@code surName}
 * is produced by a split and then consumed by an upper. Drawing one node per <em>name</em> would
 * make that a cycle, and the graph would be a lie about a DAG.
 *
 * <p>So each write produces a new value node and each read binds to the most recent earlier write,
 * falling back to the source column of that name. This is single-assignment form, and it is what
 * makes the drawing acyclic and true.
 *
 * <h2>Unresolved reads are drawn, not dropped</h2>
 *
 * <p>A step reading a name that no column declares and no earlier step writes gets an explicit
 * unbound node. The loader will not always catch it — the pipeline reads a missing value as absent
 * and produces a record with a hole in it — so the drawing is the place it becomes obvious.
 */
public final class FieldGraph {

    /** What a node is, which decides how it is drawn and what can be done to it. */
    public enum Kind {
        /** A column the source declares. */
        COLUMN,
        /** A transform step. The only kind that is edited. */
        TRANSFORM,
        /** A working value, declared scratch: never reaches the canonical record. */
        SCRATCH,
        /** A canonical field of the record this hop emits. */
        FIELD,
        /** The identity assigned to the record, derived or resolved. */
        IDENTITY,
        /** A name a step reads that nothing produces. An authoring error, drawn as one. */
        UNBOUND
    }

    /**
     * A node.
     *
     * @param stepIndex the step this node is, or is produced by; -1 for columns and unbound reads.
     *     Every edit the canvas performs is addressed by step index, so it travels with the node.
     * @param terminal whether this is the hop's final value for that name — what the contract sees
     */
    public record Node(String id, Kind kind, String label, String detail, int stepIndex,
            boolean terminal) {}

    /** A directed edge, labelled with the name being carried. */
    public record Edge(String from, String to, String label) {}

    private final String hopId;
    private final List<Node> nodes;
    private final List<Edge> edges;

    private FieldGraph(String hopId, List<Node> nodes, List<Edge> edges) {
        this.hopId = hopId;
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
    }

    public String hopId() {
        return hopId;
    }

    public List<Node> nodes() {
        return nodes;
    }

    public List<Edge> edges() {
        return edges;
    }

    /** Builds the graph for one hop of a mapping. */
    public static FieldGraph of(MappingDefinition mapping, String hopId) {
        Objects.requireNonNull(mapping, "mapping");
        HopDefinition hop = mapping.hops().stream()
                .filter(candidate -> candidate.hopId().equals(hopId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no hop '" + hopId + "'"));

        Set<String> columns = new LinkedHashSet<>(mapping.decoder().columns());
        Set<String> scratch = Set.copyOf(hop.scratch());

        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();

        // The most recent node producing each name. Reads bind to whatever is here when they run,
        // which is what makes a re-assigned target a chain rather than a cycle.
        Map<String, String> latest = new LinkedHashMap<>();
        Set<String> columnsUsed = new LinkedHashSet<>();
        Map<String, String> lastWriteNodeByName = new LinkedHashMap<>();

        List<TransformSpec> steps = hop.steps();
        for (int index = 0; index < steps.size(); index++) {
            TransformSpec step = steps.get(index);
            String transformId = "step:" + index;
            nodes.add(new Node(transformId, Kind.TRANSFORM, step.type(),
                    describeOptions(step), index, false));

            for (String input : step.from()) {
                String producer = latest.get(input);
                if (producer == null) {
                    if (columns.contains(input)) {
                        producer = "col:" + input;
                        if (columnsUsed.add(input)) {
                            nodes.add(new Node(producer, Kind.COLUMN, input, "source column", -1, false));
                        }
                    } else {
                        // Nothing produces this. The pipeline would read it as absent and emit a
                        // record with a hole; the drawing says so instead.
                        producer = "unbound:" + input;
                        if (latest.putIfAbsent("\0unbound:" + input, producer) == null) {
                            nodes.add(new Node(producer, Kind.UNBOUND, input,
                                    "nothing produces this", -1, false));
                        }
                    }
                }
                edges.add(new Edge(producer, transformId, input));
            }

            // A fresh node per write, so re-assignment reads as the chain it is.
            String valueId = "value:" + index;
            nodes.add(new Node(valueId, scratch.contains(step.target()) ? Kind.SCRATCH : Kind.FIELD,
                    step.target(), scratch.contains(step.target()) ? "scratch" : "canonical field",
                    index, false));
            edges.add(new Edge(transformId, valueId, step.target()));
            latest.put(step.target(), valueId);
            lastWriteNodeByName.put(step.target(), valueId);
        }

        // Only the last write of each name reaches the contract. Marking it lets the canvas show
        // which values are actually the hop's output rather than working state along the way.
        Set<String> terminals = Set.copyOf(lastWriteNodeByName.values());
        List<Node> marked = nodes.stream()
                .map(node -> terminals.contains(node.id())
                        ? new Node(node.id(), node.kind(), node.label(), node.detail(),
                                node.stepIndex(), true)
                        : node)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        appendIdentity(hop, lastWriteNodeByName, marked, edges);
        return new FieldGraph(hopId, marked, edges);
    }

    /**
     * Draws where the record's identity comes from.
     *
     * <p>Worth a node of its own: identity is the single most consequential thing a hop decides,
     * and "which fields decide whether these two records are the same person" is a question a
     * steward has to be able to answer by looking.
     */
    private static void appendIdentity(HopDefinition hop, Map<String, String> lastWrite,
            List<Node> nodes, List<Edge> edges) {

        IdentitySpec identity = hop.identity();
        boolean derived = identity.mode() == IdentitySpec.Mode.DERIVE;
        nodes.add(new Node("identity", Kind.IDENTITY, identity.entityType(),
                derived ? "identity derived" : "resolved by " + identity.providerId(), -1, true));

        List<String> sources = derived ? identity.deriveFrom() : List.of();
        for (String field : sources) {
            // A derive key may name a field of a hop this one depends on, which is not part of
            // this hop's own flow. Only what this hop produces is drawn.
            String producer = lastWrite.get(field);
            if (producer != null) {
                edges.add(new Edge(producer, "identity", field));
            }
        }
        if (!derived) {
            // A resolver reads the record, not named fields. Everything terminal feeds it.
            lastWrite.values().forEach(producer -> edges.add(new Edge(producer, "identity", "")));
        }
    }

    private static String describeOptions(TransformSpec step) {
        if (step.options().isEmpty()) {
            return "";
        }
        return step.options().entrySet().stream()
                .map(option -> option.getKey() + " " + abbreviate(option.getValue()))
                .reduce((a, b) -> a + " · " + b)
                .orElse("");
    }

    private static String abbreviate(String value) {
        // A codeMap's table runs to hundreds of characters. The node says one exists; the
        // inspector shows it in full.
        return value.length() <= 18 ? value : value.substring(0, 17) + "…";
    }
}
