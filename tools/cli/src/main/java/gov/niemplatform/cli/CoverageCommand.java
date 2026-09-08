package gov.niemplatform.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.niemplatform.canonical.core.CoreCanonicalTypes;
import gov.niemplatform.controlplane.NiemCoverage;
import gov.niemplatform.niem.NiemRelease;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * How much of NIEM the canonical model stands on (§4.1, ADR 0011).
 *
 * <p>The answer to "you say you are NIEM-conformant — conformant to how much of it?", from the same
 * release the build verified against, because there is only one copy of it on the classpath. Reads
 * nothing from a running deployment and needs no configuration: the model and the release both
 * travel in the jar, so this is answerable on an air-gapped workstation with no store attached.
 *
 * <p>Untouched namespaces are listed rather than filtered out. The question is what fraction of NIEM
 * the platform covers, and a report that showed only what was used would have no denominator.
 */
@Command(
        name = "coverage",
        mixinStandardHelpOptions = true,
        description = "Report how much of the NIEM release the canonical model stands on.")
final class CoverageCommand implements Callable<Integer> {

    @Option(names = "--json",
            description = "Emit JSON, for a governance tool to consume rather than a person to read.")
    boolean json;

    @Option(names = "--used-only",
            description = "List only the namespaces the model actually cites.")
    boolean usedOnly;

    @Option(names = "--namespace",
            description = "Show every citation into one namespace, by prefix or domain name.")
    String namespace;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Integer call() throws Exception {
        NiemRelease release = NiemRelease.fromClasspath();
        if (release.isEmpty()) {
            System.err.println("No NIEM release is packaged with this build, so coverage cannot be "
                    + "reported. See docs/decisions/0011-niem-reference-verification.md.");
            return 1;
        }

        NiemCoverage.Report report = NiemCoverage.of(release, CoreCanonicalTypes.ALL);

        if (namespace != null) {
            return detail(report);
        }
        if (json) {
            System.out.println(mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(asJson(report)));
            return 0;
        }
        summary(report);
        return 0;
    }

    private void summary(NiemCoverage.Report report) {
        System.out.printf("NIEM coverage: %d of %d namespaces, %d of %,d declarations cited.%n%n",
                report.namespacesTouched(), report.namespaces().size(),
                report.cited(), report.declared());

        System.out.printf("%-28s %-8s %9s %9s %9s%n",
                "DOMAIN", "PREFIX", "TYPES", "ELEMENTS", "CITED");
        for (NiemCoverage.Namespace namespace : report.namespaces()) {
            if (usedOnly && !namespace.touched()) {
                continue;
            }
            System.out.printf("%-28s %-8s %9d %9d %9s%n",
                    namespace.name(), namespace.prefix(),
                    namespace.declaredTypes(), namespace.declaredElements(),
                    namespace.touched() ? String.valueOf(namespace.cited()) : "-");
        }

        if (!report.extensions().isEmpty()) {
            // Printed with coverage, not after it. An extension is a decision the model took, and
            // separating it from the coverage it explains is how it gets read as a gap.
            System.out.printf("%nExtensions beyond NIEM (%d), each with its reason:%n",
                    report.extensions().size());
            for (NiemCoverage.Extension extension : report.extensions()) {
                System.out.printf("  %-30s %s%n", extension.where(), extension.justification());
            }
        }

        if (!report.unresolved().isEmpty()) {
            System.out.printf("%nUNRESOLVED (%d) -- the packaged release cannot account for these:%n",
                    report.unresolved().size());
            for (NiemCoverage.Unresolved problem : report.unresolved()) {
                System.out.printf("  %-30s %s: %s%n",
                        problem.where(), problem.citation(), problem.reason());
            }
        }
    }

    /** Every citation into one namespace, for reviewing what a domain was actually used for. */
    private int detail(NiemCoverage.Report report) throws Exception {
        List<NiemCoverage.Namespace> matched = report.namespaces().stream()
                .filter(candidate -> candidate.prefix().equalsIgnoreCase(namespace)
                        || candidate.name().equalsIgnoreCase(namespace))
                .toList();

        if (matched.isEmpty()) {
            System.err.println("No namespace in the release is called '" + namespace + "'. Known: "
                    + report.namespaces().stream().map(NiemCoverage.Namespace::name).sorted().toList());
            return 1;
        }

        NiemCoverage.Namespace found = matched.getFirst();
        if (json) {
            System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(asJson(found)));
            return 0;
        }

        System.out.printf("%s (%s)%n%s%n%n", found.name(), found.prefix(), found.uri());
        System.out.printf("%d type(s) and %d element(s) declared; %d cited.%n%n",
                found.declaredTypes(), found.declaredElements(), found.cited());

        if (found.cited() == 0) {
            System.out.println("The canonical model does not stand on this namespace.");
            return 0;
        }
        for (NiemCoverage.Citation citation : found.citedTypes()) {
            System.out.printf("  type     %-44s %s%n", citation.where(), citation.niemName());
        }
        for (NiemCoverage.Citation citation : found.citedElements()) {
            System.out.printf("  element  %-44s %s%n", citation.where(), citation.niemName());
        }
        return 0;
    }

    private ObjectNode asJson(NiemCoverage.Report report) {
        ObjectNode root = mapper.createObjectNode();
        root.put("namespaces", report.namespaces().size());
        root.put("namespacesTouched", report.namespacesTouched());
        root.put("declared", report.declared());
        root.put("cited", report.cited());

        ArrayNode namespaces = root.putArray("coverage");
        for (NiemCoverage.Namespace namespace : report.namespaces()) {
            if (usedOnly && !namespace.touched()) {
                continue;
            }
            namespaces.add(asJson(namespace));
        }

        ArrayNode extensions = root.putArray("extensions");
        for (NiemCoverage.Extension extension : report.extensions()) {
            ObjectNode node = extensions.addObject();
            node.put("where", extension.where());
            node.put("justification", extension.justification());
        }

        ArrayNode unresolved = root.putArray("unresolved");
        for (NiemCoverage.Unresolved problem : report.unresolved()) {
            ObjectNode node = unresolved.addObject();
            node.put("where", problem.where());
            node.put("citation", problem.citation());
            node.put("reason", problem.reason());
        }
        return root;
    }

    private ObjectNode asJson(NiemCoverage.Namespace namespace) {
        ObjectNode node = mapper.createObjectNode();
        node.put("name", namespace.name());
        node.put("prefix", namespace.prefix());
        node.put("uri", namespace.uri());
        node.put("declaredTypes", namespace.declaredTypes());
        node.put("declaredElements", namespace.declaredElements());
        node.put("cited", namespace.cited());

        ArrayNode citations = node.putArray("citations");
        namespace.citedTypes().forEach(citation -> addCitation(citations, citation, "type"));
        namespace.citedElements().forEach(citation -> addCitation(citations, citation, "element"));
        return node;
    }

    private void addCitation(ArrayNode citations, NiemCoverage.Citation citation, String kind) {
        ObjectNode node = citations.addObject();
        node.put("kind", kind);
        node.put("where", citation.where());
        node.put("niemName", citation.niemName());
    }
}
