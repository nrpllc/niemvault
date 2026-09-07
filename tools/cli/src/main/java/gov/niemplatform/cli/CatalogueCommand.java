package gov.niemplatform.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.niemplatform.controlplane.Catalogue;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * What a source sends, what the agency calls it, and what it becomes (§4.8, ADR 0019).
 *
 * <p>The structural half is assembled from artifacts that already describe themselves. The glossary
 * half comes from {@code doc:} on declared columns and contract fields, and is the half that cannot
 * be reconstructed from the data: that {@code BEAT} is operational districting rather than a postal
 * boundary, or that {@code UNK} in a licence number is an absent value wearing the shape of a
 * present one.
 *
 * <p>Gaps are reported rather than skipped. A catalogue that lists only its documented terms tells
 * an agency their source is fully understood, which is the opposite of what a catalogue is for.
 */
@Command(
        name = "catalogue",
        mixinStandardHelpOptions = true,
        description = "Report what a source sends, what it means, and what it becomes.")
final class CatalogueCommand implements Callable<Integer> {

    @Option(names = {"-m", "--module"}, required = true,
            description = "Domain module directory containing mappings/ and contracts/.")
    Path moduleDirectory;

    @Option(names = "--mapping",
            description = "One mapping to report on. Omitted: every mapping in the module.")
    Path mappingFile;

    @Option(names = "--json",
            description = "Emit JSON, for a governance tool to consume rather than a person to read.")
    boolean json;

    @Option(names = "--gaps-only",
            description = "Report only undocumented and unread terms. Exits 2 if any are found, so "
                    + "a pipeline can require a source to be documented before it is onboarded.")
    boolean gapsOnly;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Integer call() throws Exception {
        List<Path> mappings = mappingFile != null
                ? List.of(mappingFile)
                : ArtifactSet.yamlFiles(moduleDirectory.resolve("mappings"));

        if (mappings.isEmpty()) {
            System.err.println("No mappings found under " + moduleDirectory.resolve("mappings"));
            return 1;
        }

        ArrayNode all = mapper.createArrayNode();
        boolean anyGaps = false;

        for (Path mapping : mappings) {
            ArtifactSet artifacts = ArtifactSet.load(mapping, moduleDirectory.resolve("contracts"));
            Catalogue.Source source = Catalogue.of(
                    artifacts.mapping(),
                    artifacts.contractsByHop(),
                    List.copyOf(artifacts.canonicalTypes().values()));

            anyGaps |= !source.undocumented().isEmpty() || !source.unused().isEmpty();

            if (json) {
                all.add(asJson(source));
            } else if (gapsOnly) {
                printGaps(source);
            } else {
                print(source);
            }
        }

        if (json) {
            System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(all));
        }

        // Exit 2 for "reported, and there is something to fix" -- the same convention `run` uses for
        // a run that quarantined records. A gate can require a documented source before onboarding
        // it without having to parse the output.
        return gapsOnly && anyGaps ? 2 : 0;
    }

    private void print(Catalogue.Source source) {
        System.out.printf("%s%n", source.sourceId());
        System.out.printf("  mapping   %s@%s%n", source.mappingName(), source.mappingVersion());
        System.out.printf("  record    %s%n", source.recordType());
        source.contracts().forEach(contract -> System.out.printf("  contract  %s%n", contract));

        System.out.println();
        System.out.println("  Vocabulary");
        for (Catalogue.Term term : source.vocabulary()) {
            System.out.printf("    %-12s %s%n", term.term(),
                    term.becomes().isEmpty() ? "(read by nothing)" : String.join(", ", term.becomes()));
            System.out.printf("      %s%n", term.meaning().orElse("-- not documented --"));
        }

        System.out.println();
        System.out.println("  Produces");
        for (Catalogue.Produced produced : source.produces()) {
            System.out.printf("    %-28s %s%n", produced.name() + "@" + produced.version(),
                    produced.identity());
            System.out.printf("      %s%n", produced.provenance());
        }

        printGaps(source);
        System.out.println();
    }

    private void printGaps(Catalogue.Source source) {
        if (source.undocumented().isEmpty() && source.unused().isEmpty()) {
            System.out.printf("  %s: every term documented and read.%n", source.sourceId());
            return;
        }
        System.out.println();
        if (!source.undocumented().isEmpty()) {
            System.out.printf("  Undocumented (%d): %s%n",
                    source.undocumented().size(), String.join(", ", source.undocumented()));
            System.out.println("    Nobody has said what these mean. They cannot be reviewed by "
                    + "anyone who did not write the mapping.");
        }
        if (!source.unused().isEmpty()) {
            System.out.printf("  Sent but never read (%d): %s%n",
                    source.unused().size(), String.join(", ", source.unused()));
            System.out.println("    The source sends these and no step consumes them. Either the "
                    + "mapping is incomplete, or the source is sending more than was asked for.");
        }
    }

    private ObjectNode asJson(Catalogue.Source source) {
        ObjectNode node = mapper.createObjectNode();
        node.put("sourceId", source.sourceId());
        node.put("mapping", source.mappingName() + "@" + source.mappingVersion());
        node.put("recordType", source.recordType());

        ArrayNode contracts = node.putArray("contracts");
        source.contracts().forEach(contracts::add);

        ArrayNode vocabulary = node.putArray("vocabulary");
        for (Catalogue.Term term : source.vocabulary()) {
            ObjectNode entry = vocabulary.addObject();
            entry.put("term", term.term());
            // Explicitly null rather than absent: a consumer must be able to tell "nobody said"
            // from "the key is missing from this version of the output".
            entry.put("meaning", term.meaning().orElse(null));
            entry.put("governed", term.governed());
            ArrayNode becomes = entry.putArray("becomes");
            term.becomes().forEach(becomes::add);
        }

        ArrayNode produces = node.putArray("produces");
        for (Catalogue.Produced produced : source.produces()) {
            ObjectNode entry = produces.addObject();
            entry.put("type", produced.name());
            entry.put("version", produced.version());
            entry.put("provenance", produced.provenance());
            entry.put("byHop", produced.byHop());
            entry.put("identity", produced.identity());
            ArrayNode fields = entry.putArray("fields");
            produced.fields().forEach(fields::add);
        }

        ObjectNode gaps = node.putObject("gaps");
        ArrayNode undocumented = gaps.putArray("undocumented");
        source.undocumented().forEach(undocumented::add);
        ArrayNode unused = gaps.putArray("unused");
        source.unused().forEach(unused::add);
        return node;
    }
}
