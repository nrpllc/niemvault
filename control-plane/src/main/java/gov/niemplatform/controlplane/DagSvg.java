package gov.niemplatform.controlplane;

import gov.niemplatform.runtime.engine.HopDefinition;
import gov.niemplatform.runtime.engine.IdentitySpec;
import gov.niemplatform.runtime.engine.MappingDefinition;
import gov.niemplatform.runtime.transforms.TransformSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders a mapping's DAG as SVG, on the server.
 *
 * <p>No JavaScript diagramming library. Spec §6 makes air-gapped delivery mandatory, which rules
 * out fetching one at runtime, and vendoring a rendering engine to draw a graph with a handful of
 * nodes is a poor trade. A mapping DAG is small by construction — it is one source shape fanning
 * out to a few canonical types — so a layered layout is a page of arithmetic rather than a layout
 * problem.
 *
 * <p>Colours come from the page's CSS through class names rather than being baked in, so the
 * diagram follows the theme instead of fighting it.
 */
final class DagSvg {

    private static final int COLUMN_WIDTH = 260;
    private static final int NODE_WIDTH = 190;
    private static final int ROW_HEIGHT = 108;
    private static final int NODE_HEIGHT = 66;
    private static final int MARGIN_X = 28;
    private static final int MARGIN_Y = 28;

    private DagSvg() {}

    /**
     * A laid-out node, before it becomes markup.
     *
     * @param hopId the hop this node belongs to, or null for the source. Carried into the markup
     *     so clicking the graph can select the steps that produce the node.
     */
    private record Node(String id, int column, int row, String title, String subtitle, String kind,
            String hopId) {

        int x() {
            return MARGIN_X + column * COLUMN_WIDTH;
        }

        int y() {
            return MARGIN_Y + row * ROW_HEIGHT;
        }

        int centreY() {
            return y() + NODE_HEIGHT / 2;
        }

        int rightX() {
            return x() + NODE_WIDTH;
        }
    }

    static String render(MappingDefinition mapping) {
        List<HopDefinition> hops = mapping.hopsInDependencyOrder();
        Map<String, Integer> depth = depths(hops);
        int maxDepth = depth.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        List<Node> nodes = new ArrayList<>();
        Map<String, Node> hopNodes = new LinkedHashMap<>();
        Map<String, Node> outputNodes = new LinkedHashMap<>();

        Node source = new Node("source", 0, rowForSource(hops.size()),
                mapping.decoder().emitsType(),
                mapping.decoder().columns().size() + " columns", "source", null);
        nodes.add(source);

        Map<Integer, Integer> rowsUsed = new HashMap<>();
        for (HopDefinition hop : hops) {
            int column = 1 + depth.getOrDefault(hop.hopId(), 0);
            int row = rowsUsed.merge(column, 1, Integer::sum) - 1;
            Node node = new Node("hop-" + hop.hopId(), column, row, hop.hopId(),
                    hop.steps().size() + " steps", "hop", hop.hopId());
            hopNodes.put(hop.hopId(), node);
            nodes.add(node);
        }

        int outputColumn = maxDepth + 2;
        int outputRow = 0;
        for (HopDefinition hop : hops) {
            Node node = new Node("out-" + hop.hopId(), outputColumn, outputRow++,
                    hop.identity().entityType(), identityLabel(hop.identity()), "canonical",
                    hop.hopId());
            outputNodes.put(hop.hopId(), node);
            nodes.add(node);
        }

        int width = MARGIN_X * 2 + (outputColumn + 1) * COLUMN_WIDTH;
        int height = MARGIN_Y * 2 + Math.max(hops.size(), 3) * ROW_HEIGHT;

        StringBuilder svg = new StringBuilder();
        svg.append("<svg class=\"dag\" viewBox=\"0 0 ").append(width).append(' ').append(height)
                .append("\" width=\"").append(width).append("\" height=\"").append(height)
                .append("\" xmlns=\"http://www.w3.org/2000/svg\" role=\"img\" ")
                .append("aria-label=\"Mapping data flow\">\n");
        svg.append(arrowMarkers());

        for (HopDefinition hop : hops) {
            Node hopNode = hopNodes.get(hop.hopId());
            svg.append(edge(source, hopNode, consumedColumns(mapping, hop), "consumes"));
            svg.append(edge(hopNode, outputNodes.get(hop.hopId()), "", "emits"));
            for (String dependency : hop.dependsOn()) {
                svg.append(edge(outputNodes.get(dependency), hopNode, "identity", "identity"));
            }
        }

        nodes.forEach(node -> svg.append(node(node)));
        svg.append("</svg>");
        return svg.toString();
    }

    private static int rowForSource(int hopCount) {
        return Math.max(0, (hopCount - 1) / 2);
    }

    /** Depth of each hop: zero when it reads only the source, otherwise one past its deepest input. */
    private static Map<String, Integer> depths(List<HopDefinition> hops) {
        Map<String, Integer> depth = new LinkedHashMap<>();
        for (HopDefinition hop : hops) {
            int deepest = hop.dependsOn().stream()
                    .mapToInt(dependency -> depth.getOrDefault(dependency, 0) + 1)
                    .max()
                    .orElse(0);
            depth.put(hop.hopId(), deepest);
        }
        return depth;
    }

    /**
     * Source columns a hop reads.
     *
     * <p>A step may name a source column or something an earlier step in the same hop emitted, and
     * only the former is a source column. Telling them apart is the most useful thing the diagram
     * says.
     */
    private static String consumedColumns(MappingDefinition mapping, HopDefinition hop) {
        Set<String> declared = new LinkedHashSet<>(mapping.decoder().columns());
        Set<String> consumed = new LinkedHashSet<>();
        for (TransformSpec step : hop.steps()) {
            step.from().stream().filter(declared::contains).forEach(consumed::add);
        }
        if (consumed.isEmpty()) {
            return "";
        }
        String joined = String.join(", ", consumed);
        return joined.length() > 34 ? joined.substring(0, 32) + "..." : joined;
    }

    private static String identityLabel(IdentitySpec identity) {
        return switch (identity.mode()) {
            case RESOLVE -> "resolved";
            case DERIVE -> "derived";
        };
    }

    private static String arrowMarkers() {
        return """
                  <defs>
                    <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5"
                            markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                      <path d="M 0 0 L 10 5 L 0 10 z" class="dag-arrow"/>
                    </marker>
                  </defs>
                """;
    }

    private static String node(Node node) {
        StringBuilder out = new StringBuilder();
        out.append("  <g class=\"dag-node dag-node--").append(node.kind()).append('"');
        if (node.hopId() != null) {
            // Carried into the markup so a click on the graph can select the steps behind it.
            out.append(" data-hop=\"").append(escape(node.hopId())).append('"');
        }
        out.append(">\n");
        out.append("    <rect x=\"").append(node.x()).append("\" y=\"").append(node.y())
                .append("\" width=\"").append(NODE_WIDTH).append("\" height=\"").append(NODE_HEIGHT)
                .append("\" rx=\"2\"/>\n");
        out.append("    <text class=\"dag-title\" x=\"").append(node.x() + 12)
                .append("\" y=\"").append(node.y() + 26).append("\">")
                .append(escape(truncate(node.title(), 24))).append("</text>\n");
        out.append("    <text class=\"dag-subtitle\" x=\"").append(node.x() + 12)
                .append("\" y=\"").append(node.y() + 46).append("\">")
                .append(escape(truncate(node.subtitle(), 26))).append("</text>\n");
        out.append("  </g>\n");
        return out.toString();
    }

    private static String edge(Node from, Node to, String label, String kind) {
        int x1 = from.rightX();
        int y1 = from.centreY();
        int x2 = to.x();
        int y2 = to.centreY();
        int midX = x1 + (x2 - x1) / 2;

        StringBuilder out = new StringBuilder();
        out.append("  <path class=\"dag-edge dag-edge--").append(kind).append("\" d=\"M ")
                .append(x1).append(' ').append(y1)
                .append(" C ").append(midX).append(' ').append(y1)
                .append(", ").append(midX).append(' ').append(y2)
                .append(", ").append(x2).append(' ').append(y2)
                .append("\" marker-end=\"url(#arrow)\"/>\n");

        if (!label.isEmpty()) {
            out.append("  <text class=\"dag-edge-label\" x=\"").append(midX)
                    .append("\" y=\"").append((y1 + y2) / 2 - 6)
                    .append("\" text-anchor=\"middle\">").append(escape(label)).append("</text>\n");
        }
        return out.toString();
    }

    private static String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
