package gov.niemplatform.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.content.SemanticVersion;
import gov.niemplatform.runtime.engine.MappingDefinition;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The field-level flow, built from the law enforcement module's real mapping.
 *
 * <p>What matters here is that the drawing tells the truth about a DSL where steps assign in order
 * and later steps read earlier results. Getting that wrong produces a picture with a cycle in it,
 * which is both wrong and unlayoutable.
 */
class FieldGraphTest {

    private static final MappingWorkspace WORKSPACE = new MappingWorkspace(
            Path.of("").toAbsolutePath().getParent()
                    .resolve("modules/law-enforcement/src/main/resources"),
            SemanticVersion.parse("0.1.0"));

    private static MappingDefinition mapping() {
        return WORKSPACE.load("cad-to-canonical-1.0.0.yaml");
    }

    private static FieldGraph graph(String hopId) {
        return FieldGraph.of(mapping(), hopId);
    }

    private static List<FieldGraph.Node> ofKind(FieldGraph graph, FieldGraph.Kind kind) {
        return graph.nodes().stream().filter(node -> node.kind() == kind).toList();
    }

    @Nested
    @DisplayName("Shape")
    class Shape {

        @Test
        @DisplayName("draws a node per step and a node per value that step writes")
        void oneNodePerStep() {
            FieldGraph graph = graph("map-incident");
            var hop = mapping().hops().stream()
                    .filter(candidate -> candidate.hopId().equals("map-incident")).findFirst().orElseThrow();

            assertThat(ofKind(graph, FieldGraph.Kind.TRANSFORM)).hasSameSizeAs(hop.steps());
            assertThat(ofKind(graph, FieldGraph.Kind.FIELD)).hasSameSizeAs(hop.steps());
        }

        @Test
        @DisplayName("draws only the source columns the hop actually reads")
        void drawsOnlyColumnsUsed() {
            // The source declares ten columns; map-incident reads five of them. Drawing all ten
            // would put five dead nodes on the canvas.
            FieldGraph graph = graph("map-incident");

            assertThat(ofKind(graph, FieldGraph.Kind.COLUMN))
                    .extracting(FieldGraph.Node::label)
                    .containsExactlyInAnyOrder("INC_NUM", "RPT_DTTM", "ADDR", "CALL_TYPE", "BEAT");
        }

        @Test
        @DisplayName("reports an unknown hop rather than drawing an empty canvas")
        void refusesAnUnknownHop() {
            assertThatThrownBy(() -> graph("map-nothing"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no hop 'map-nothing'");
        }
    }

    @Nested
    @DisplayName("Re-assignment")
    class Reassignment {

        @Test
        @DisplayName("a target written twice becomes a chain, not a cycle")
        void reassignmentIsAChain() {
            // map-person writes surName by splitting NAME_FULL, then writes it again by uppercasing
            // what it just wrote. One node per name would make that edge point back at itself.
            FieldGraph graph = graph("map-person");

            List<FieldGraph.Node> surNames = graph.nodes().stream()
                    .filter(node -> node.label().equals("surName")).toList();
            assertThat(surNames).hasSize(2);

            FieldGraph.Node first = surNames.get(0);
            FieldGraph.Node second = surNames.get(1);
            // The second write's transform reads the first write's value.
            String upper = graph.edges().stream()
                    .filter(edge -> edge.from().equals(first.id()))
                    .map(FieldGraph.Edge::to)
                    .findFirst()
                    .orElseThrow();
            assertThat(graph.edges())
                    .contains(new FieldGraph.Edge(upper, second.id(), "surName"));
        }

        @Test
        @DisplayName("only the last write of a name is terminal")
        void onlyTheLastWriteIsTerminal() {
            // The contract sees the final value. Marking both would suggest the hop emits surName
            // twice, and marking neither would leave nothing identifiable as output.
            FieldGraph graph = graph("map-person");

            List<FieldGraph.Node> surNames = graph.nodes().stream()
                    .filter(node -> node.label().equals("surName")).toList();
            assertThat(surNames.get(0).terminal()).isFalse();
            assertThat(surNames.get(1).terminal()).isTrue();
        }

        @Test
        @DisplayName("the graph is acyclic, which is the whole point of versioning writes")
        void isAcyclic() {
            for (var hop : mapping().hops()) {
                FieldGraph graph = FieldGraph.of(mapping(), hop.hopId());
                assertThat(hasCycle(graph)).as("hop %s", hop.hopId()).isFalse();
            }
        }

        private static boolean hasCycle(FieldGraph graph) {
            java.util.Map<String, List<String>> out = new java.util.HashMap<>();
            graph.edges().forEach(edge ->
                    out.computeIfAbsent(edge.from(), key -> new java.util.ArrayList<>()).add(edge.to()));

            java.util.Set<String> visiting = new java.util.HashSet<>();
            java.util.Set<String> done = new java.util.HashSet<>();
            for (FieldGraph.Node node : graph.nodes()) {
                if (walk(node.id(), out, visiting, done)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean walk(String id, java.util.Map<String, List<String>> out,
                java.util.Set<String> visiting, java.util.Set<String> done) {
            if (done.contains(id)) {
                return false;
            }
            if (!visiting.add(id)) {
                return true;
            }
            for (String next : out.getOrDefault(id, List.of())) {
                if (walk(next, out, visiting, done)) {
                    return true;
                }
            }
            visiting.remove(id);
            done.add(id);
            return false;
        }
    }

    @Nested
    @DisplayName("Scratch and canonical")
    class Kinds {

        @Test
        @DisplayName("distinguishes a working value from a field of the record")
        void distinguishesScratch() {
            // givenNames is declared scratch: it exists to be split further and never reaches the
            // canonical Person. Drawing it identically to a real field would misrepresent the hop.
            FieldGraph graph = graph("map-person");

            assertThat(ofKind(graph, FieldGraph.Kind.SCRATCH))
                    .extracting(FieldGraph.Node::label)
                    .containsOnly("givenNames");
            assertThat(ofKind(graph, FieldGraph.Kind.FIELD))
                    .extracting(FieldGraph.Node::label)
                    .doesNotContain("givenNames");
        }
    }

    @Nested
    @DisplayName("Unbound reads")
    class Unbound {

        @Test
        @DisplayName("a step reading a name nothing produces is drawn as broken")
        void drawsAnUnboundRead() {
            // The loader accepts this: the pipeline reads a missing value as absent and emits a
            // record with a hole in it. The canvas is where it should become obvious.
            var report = WORKSPACE.validate(
                    WORKSPACE.source("cad-to-canonical-1.0.0.yaml").replace("from: [INC_NUM]", "from: [INC_NUMBR]"));
            assertThat(report.definition()).isNotNull();

            FieldGraph graph = FieldGraph.of(report.definition(), "map-incident");

            assertThat(ofKind(graph, FieldGraph.Kind.UNBOUND))
                    .extracting(FieldGraph.Node::label)
                    .containsExactly("INC_NUMBR");
        }
    }

    @Nested
    @DisplayName("Identity")
    class Identity {

        @Test
        @DisplayName("shows which fields decide the record's identity")
        void showsDerivedIdentity() {
            // "Which fields decide whether these two records are the same person" is a question a
            // steward must be able to answer by looking at the picture.
            FieldGraph graph = graph("map-incident");

            FieldGraph.Node identity = ofKind(graph, FieldGraph.Kind.IDENTITY).getFirst();
            assertThat(identity.label()).isEqualTo("Incident");

            List<String> feeding = graph.edges().stream()
                    .filter(edge -> edge.to().equals("identity"))
                    .map(FieldGraph.Edge::label)
                    .toList();
            assertThat(feeding).containsExactly("incidentNumber");
        }

        @Test
        @DisplayName("names the resolver when identity is resolved rather than derived")
        void showsResolvedIdentity() {
            FieldGraph graph = graph("map-person");

            FieldGraph.Node identity = ofKind(graph, FieldGraph.Kind.IDENTITY).getFirst();
            assertThat(identity.label()).isEqualTo("Person");
            assertThat(identity.detail()).contains("resolved by");
        }
    }
}
