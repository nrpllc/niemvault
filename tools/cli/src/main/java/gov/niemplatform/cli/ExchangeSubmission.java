package gov.niemplatform.cli;

import gov.niemplatform.canonical.core.CoreCanonicalTypes;
import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.exchange.api.AssembledDocument;
import gov.niemplatform.exchange.api.DocumentAssembler;
import gov.niemplatform.exchange.api.ExchangeDefinition;
import gov.niemplatform.exchange.api.ExchangeRegistry;
import gov.niemplatform.exchange.api.ExchangeWriter;
import gov.niemplatform.exchange.api.SubmissionOutcome;
import gov.niemplatform.exchange.api.SubmissionReceipt;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Submits a run's canonical records to an external system of record (ADR 0034).
 *
 * <p>Replaces {@code --cch-url}, which named one repository on the command line and built a writer
 * for it by name. That is the shape ADR 0029 rejected for sources and rejected for the same reason:
 * the flag list grows by one exchange per exchange, and the settings behind each of them end up in
 * an operator's shell history rather than in a reviewable file.
 *
 * <p>So {@code --exchange <file>} reads an artifact, the registry resolves its writer, and the
 * assembly in the file decides what is sent. Adding a repository is shipping a jar and writing a
 * file; changing what is submitted to one is editing the file alone.
 *
 * <h2>Why records are collected rather than streamed</h2>
 *
 * <p>A document is assembled by following associations, and an association can only be followed
 * once both of its ends have been produced. A charge streamed before its arrest would assemble into
 * nothing. So a run's canonical output is held and assembled at the end -- the same thing
 * {@code ProjectionFanout} does, and for the same reason.
 */
final class ExchangeSubmission implements AutoCloseable {

    private final ExchangeDefinition definition;
    private final ExchangeWriter writer;
    private final List<Record> canonical = new ArrayList<>();

    private ExchangeSubmission(ExchangeDefinition definition, ExchangeWriter writer) {
        this.definition = definition;
        this.writer = writer;
    }

    /**
     * Loads an exchange artifact and resolves its writer.
     *
     * <p>The assembly is validated against the canonical model here, before a single record is
     * read. A misspelled role would otherwise submit a document whose elements are simply absent,
     * which a repository accepts without complaint.
     */
    static ExchangeSubmission open(Path file) {
        ExchangeDefinition definition = ExchangeDefinition.load(file, CoreCanonicalTypes.ALL);
        return new ExchangeSubmission(definition, ExchangeRegistry.discover().writerFor(definition));
    }

    /** The source this exchange declares it sends, checked against the mapping before a run. */
    String sourceId() {
        return definition.sourceId();
    }

    String describe() {
        return definition.exchangeName() + "@" + definition.version()
                + " over " + definition.type() + ", rooted on " + definition.assemble().rootType();
    }

    /** Asked before anything is landed, so an unreachable repository is not found out mid-run. */
    boolean isHealthy(PrintStream err) {
        var health = writer.health();
        if (health.isHealthy()) {
            return true;
        }
        err.printf("Exchange '%s' is %s%n", definition.exchangeName(), health);
        return false;
    }

    void acceptAll(List<Record> records) {
        canonical.addAll(records);
    }

    /**
     * Assembles and submits, then reports what the far side said.
     *
     * @return false if anything was rejected, so a run can exit non-zero on it
     */
    boolean submit(PrintStream out, PrintStream err) {
        DocumentAssembler.Assembly assembly =
                new DocumentAssembler(CoreCanonicalTypes.ALL)
                        .assemble(definition.assemble(), canonical);

        List<AssembledDocument> documents = assembly.documents();
        out.println();
        out.printf("Exchange %s%n", describe());
        out.printf("  assembled          %,d document(s) from %,d record(s)%n",
                documents.size(), assembly.records());

        // Reported, never swallowed. A disposition that has not arrived yet and an identity that
        // never resolved look identical from inside the assembler, and only an operator can tell
        // which this is.
        if (!assembly.unresolved().isEmpty()) {
            out.printf("  unresolved links   %,d (an element pointed at a record this run did not "
                    + "produce)%n", assembly.unresolved().size());
            assembly.unresolved().stream().limit(5).forEach(missing ->
                    out.printf("    %s %s -> %s.%s %s%n", missing.fromType(), missing.fromId(),
                            missing.association(), missing.role(), missing.targetRef()));
        }

        if (documents.isEmpty()) {
            out.println("  nothing to submit");
            return true;
        }

        List<SubmissionReceipt> receipts = writer.submit(documents);
        Map<SubmissionOutcome, Integer> counts = new EnumMap<>(SubmissionOutcome.class);
        receipts.forEach(receipt ->
                counts.merge(receipt.outcome(), 1, Integer::sum));
        counts.forEach((outcome, count) ->
                out.printf("  %-18s %,d%n", outcome.name().toLowerCase(java.util.Locale.ROOT), count));

        List<SubmissionReceipt> rejected = receipts.stream()
                .filter(receipt -> receipt.outcome() == SubmissionOutcome.REJECTED)
                .toList();
        if (!rejected.isEmpty()) {
            err.println();
            err.printf("%,d document(s) were refused by the repository:%n", rejected.size());
            rejected.forEach(receipt ->
                    err.printf("  %s: %s%n", receipt.documentId(), receipt.detail()));
        }
        return rejected.isEmpty();
    }

    @Override
    public void close() {
        writer.close();
    }
}
