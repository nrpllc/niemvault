package gov.niemplatform.cli;

import gov.niemplatform.contracts.HopContract;
import gov.niemplatform.runtime.engine.HopDefinition;
import gov.niemplatform.runtime.engine.MappingDefinition;
import gov.niemplatform.runtime.transforms.TransformSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Renders a mapping's data flow.
 *
 * <p>A mapping is a DAG, and reading it as YAML means holding the graph in your head. Rendering it
 * answers the questions people actually ask of a mapping in review: which source columns feed
 * which canonical type, where identity comes from, and what depends on what.
 *
 * <p>Not a client application, which spec §8 puts out of Phase 1 scope. This is operator tooling
 * that prints a diagram to standard output — the same category as {@code validate}, and it works
 * offline, which matters because the air-gapped delivery mode (§6) rules out anything that needs a
 * hosted renderer.
 *
 * <p>Mermaid because it renders inline in GitHub, in a pull request, and in most documentation
 * tools, so a mapping change can be reviewed as a picture by whoever owns the source without them
 * installing anything.
 */
@Command(
        name = "describe",
        mixinStandardHelpOptions = true,
        description = "Render a mapping's data flow as a diagram or as text.")
final class DescribeCommand implements Callable<Integer> {

    /** Output shapes. */
    enum Format {
        /** Mermaid flowchart, renderable in GitHub and most documentation tools. */
        MERMAID,
        /** Plain text, for a terminal. */
        TEXT
    }

    @Option(names = {"-m", "--module"}, required = true,
            description = "Domain module directory containing mappings/ and contracts/.")
    Path moduleDirectory;

    @Option(names = "--mapping", required = true, description = "Mapping artifact to describe.")
    Path mappingFile;

    @Option(names = "--format", defaultValue = "MERMAID",
            description = "Output format: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}")
    Format format;

    @Option(names = "--out", description = "Write here instead of standard output.")
    Path outputFile;

    @Option(names = "--show-steps",
            description = "Label each hop with its transformation steps, not just a count.")
    boolean showSteps;

    @Override
    public Integer call() throws IOException {
        ArtifactSet artifacts = ArtifactSet.load(
                mappingFile.toAbsolutePath().normalize(),
                moduleDirectory.toAbsolutePath().normalize().resolve("contracts"));

        String rendered = switch (format) {
            case MERMAID -> mermaid(artifacts);
            case TEXT -> text(artifacts);
        };

        if (outputFile == null) {
            System.out.println(rendered);
        } else {
            Files.writeString(outputFile, rendered, StandardCharsets.UTF_8);
            System.out.printf("Wrote %s.%n", outputFile);
        }
        return 0;
    }

    // --- mermaid ---------------------------------------------------------

    private String mermaid(ArtifactSet artifacts) {
        MappingDefinition mapping = artifacts.mapping();
        StringBuilder out = new StringBuilder();

        out.append("```mermaid\n");
        out.append("flowchart LR\n");
        out.append("  %% ").append(mapping.qualifiedName())
                .append("  --  source ").append(mapping.sourceId()).append("\n\n");

        out.append("  subgraph bronze[\"bronze&nbsp;&mdash;&nbsp;byte-preserved\"]\n");
        out.append("    payload[\"raw payload\"]\n");
        out.append("  end\n\n");

        out.append("  subgraph decode[\"decode\"]\n");
        out.append("    source[\"").append(escape(mapping.decoder().emitsType()))
                .append("<br/><small>").append(mapping.decoder().columns().size())
                .append(" declared columns</small>\"]\n");
        out.append("  end\n\n");
        out.append("  payload --> source\n\n");

        out.append("  subgraph silver[\"silver&nbsp;&mdash;&nbsp;canonical\"]\n");
        for (HopDefinition hop : mapping.hopsInDependencyOrder()) {
            String canonical = nodeId("out", hop.hopId());
            out.append("    ").append(canonical).append("([\"")
                    .append(escape(hop.identity().entityType())).append("\"])\n");
        }
        out.append("  end\n\n");

        for (HopDefinition hop : mapping.hopsInDependencyOrder()) {
            String hopNode = nodeId("hop", hop.hopId());
            out.append("  ").append(hopNode).append("[\"")
                    .append(escape(hop.hopId()))
                    .append("<br/><small>").append(escape(contractLabel(artifacts, hop)))
                    .append("</small>")
                    .append(showSteps ? "<br/><small>" + escape(stepSummary(hop)) + "</small>"
                            : "<br/><small>" + hop.steps().size() + " steps</small>")
                    .append("<br/><small>id: ").append(escape(identityLabel(hop)))
                    .append("</small>\"]\n");
        }
        out.append("\n");

        // Which source columns each hop actually consumes. The question a records manager asks
        // first, and the one a YAML file answers least well.
        for (HopDefinition hop : mapping.hopsInDependencyOrder()) {
            Set<String> consumed = consumedColumns(mapping, hop);
            String label = consumed.isEmpty() ? "" : "|" + escape(String.join(", ", consumed)) + "|";
            out.append("  source -->").append(label).append(" ")
                    .append(nodeId("hop", hop.hopId())).append("\n");
        }
        out.append("\n");

        for (HopDefinition hop : mapping.hopsInDependencyOrder()) {
            out.append("  ").append(nodeId("hop", hop.hopId()))
                    .append(" --> ").append(nodeId("out", hop.hopId())).append("\n");

            // A dependency is drawn from the upstream canonical output, not from the upstream hop:
            // what a dependent hop actually reads is the identity that hop assigned.
            for (String dependency : hop.dependsOn()) {
                out.append("  ").append(nodeId("out", dependency))
                        .append(" -. identity .-> ").append(nodeId("hop", hop.hopId())).append("\n");
            }
        }

        out.append("\n  quarantine[[\"quarantine<br/><small>contract violations</small>\"]]\n");
        for (HopDefinition hop : mapping.hopsInDependencyOrder()) {
            out.append("  ").append(nodeId("hop", hop.hopId()))
                    .append(" -. on violation .-> quarantine\n");
        }

        out.append("```\n");
        return out.toString();
    }

    // --- text ------------------------------------------------------------

    private String text(ArtifactSet artifacts) {
        MappingDefinition mapping = artifacts.mapping();
        StringBuilder out = new StringBuilder();

        out.append(mapping.qualifiedName()).append("\n");
        out.append("  source   ").append(mapping.sourceId()).append("\n");
        out.append("  decode   ").append(mapping.decoder().emitsType())
                .append("  (").append(mapping.decoder().columns().size()).append(" columns: ")
                .append(String.join(", ", mapping.decoder().columns())).append(")\n\n");

        for (HopDefinition hop : mapping.hopsInDependencyOrder()) {
            out.append("  hop ").append(hop.hopId()).append("\n");
            out.append("      contract  ").append(contractLabel(artifacts, hop)).append("\n");
            out.append("      emits     ").append(hop.identity().entityType()).append("\n");
            out.append("      identity  ").append(identityLabel(hop)).append("\n");
            out.append("      consumes  ")
                    .append(String.join(", ", consumedColumns(mapping, hop))).append("\n");
            if (!hop.dependsOn().isEmpty()) {
                out.append("      after     ").append(String.join(", ", hop.dependsOn())).append("\n");
            }
            if (!hop.roles().isEmpty()) {
                hop.roles().forEach((role, from) ->
                        out.append("      role      ").append(role).append(" <- ").append(from).append("\n"));
            }
            if (showSteps) {
                for (TransformSpec step : hop.steps()) {
                    out.append("        ").append(String.format(Locale.ROOT, "%-22s", step.target()))
                            .append(step.type())
                            .append(step.from().isEmpty() ? "" : " <- " + String.join(", ", step.from()))
                            .append(hop.scratch().contains(step.target()) ? "   (scratch)" : "")
                            .append("\n");
                }
            }
            out.append("\n");
        }
        return out.toString();
    }

    // --- helpers ---------------------------------------------------------

    /**
     * Source columns a hop reads.
     *
     * <p>A step's inputs may name either a declared source column or a value an earlier step in
     * the same hop emitted; only the former is a source column, and telling them apart is the
     * whole point of showing this.
     */
    private static Set<String> consumedColumns(MappingDefinition mapping, HopDefinition hop) {
        Set<String> declared = new LinkedHashSet<>(mapping.decoder().columns());
        Set<String> consumed = new LinkedHashSet<>();
        for (TransformSpec step : hop.steps()) {
            step.from().stream().filter(declared::contains).forEach(consumed::add);
        }
        return consumed;
    }

    private static String contractLabel(ArtifactSet artifacts, HopDefinition hop) {
        HopContract contract = artifacts.contractsByHop().get(hop.hopId());
        return contract == null
                ? hop.contractName() + "@" + hop.contractVersion() + " (missing)"
                : contract.id().toString();
    }

    private static String identityLabel(HopDefinition hop) {
        return switch (hop.identity().mode()) {
            case RESOLVE -> "resolved by " + hop.identity().providerId();
            case DERIVE -> "derived from " + String.join(" + ", hop.identity().deriveFrom());
        };
    }

    private static String stepSummary(HopDefinition hop) {
        List<String> steps = new ArrayList<>();
        hop.steps().forEach(step -> steps.add(step.target() + " = " + step.type()));
        return String.join("<br/>", steps);
    }

    /** Mermaid node identifiers must be alphanumeric; hop ids contain hyphens. */
    private static String nodeId(String prefix, String hopId) {
        return prefix + "_" + hopId.replaceAll("[^A-Za-z0-9]", "_");
    }

    /** Mermaid treats several characters structurally; labels have to be escaped. */
    private static String escape(String text) {
        return text == null ? "" : text
                .replace("\"", "&quot;")
                .replace("#", "&num;")
                .replace("|", "&#124;");
    }
}
