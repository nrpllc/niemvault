package gov.niemplatform.exchange.api;

import java.util.List;

/**
 * Submits assembled documents to an external system of record (ADR 0034).
 *
 * <p>Deliberately not a {@code ProjectionWriter}, though the first
 * criminal history writer was one and argued the case well: gold is polymorphic (§4.6), a
 * repository consumes canonical records, so a repository is another shape of gold.
 *
 * <p>The argument breaks on one method. {@code ProjectionWriter.rebuild} must <em>replace</em> the
 * projection from a complete snapshot of silver, and it must do so because criterion 6 turns on a
 * projection being reconstructible. Against a graph or a search index that is a correct and routine
 * operation. Against a state criminal history repository it means resubmitting every arrest the
 * agency has ever made -- which is not a rebuild, it is an incident, and the repository has no
 * obligation to tolerate it. A projection this platform can rebuild and a record system it can only
 * append to are different things, and one interface covering both would have to be honest about
 * only one of them.
 *
 * <p>So submission gets its own contract, and the differences are the point of it: a submission is
 * acknowledged or rejected by a party that is not this platform, asynchronously, days later,
 * against an identifier the platform has to keep.
 *
 * <p>Implementations are discovered through {@link java.util.ServiceLoader}, so an agency ships a
 * wire format as a jar. What that format <em>contains</em> is the exchange definition's business,
 * never this class's: a writer that knew which canonical types it carried would be the hard-coded
 * writer this SPI replaced.
 */
public interface ExchangeWriter extends AutoCloseable {

    /** The wire format this writer speaks. */
    ExchangeType type();

    /**
     * Applies the exchange's configuration.
     *
     * @throws ExchangeDefinitionException if the settings are unusable, naming every problem
     */
    void configure(ExchangeDefinition definition);

    /**
     * Submits assembled documents.
     *
     * <p>Returns a receipt per document rather than one for the batch, because a repository accepts
     * and rejects individually: one malformed charge does not invalidate the other forty arrests in
     * the same send, and reporting a whole batch as failed would have an operator resubmit records
     * that were already posted.
     */
    List<SubmissionReceipt> submit(List<AssembledDocument> documents);

    /**
     * Whether the far side is reachable.
     *
     * <p>Asked before a submission rather than discovered during one. A submission that fails
     * halfway leaves an operator working out which half, against a system they cannot query freely.
     */
    ExchangeHealth health();

    /** The settings an exchange of this type carries (ADR 0037). Empty by default. */
    default java.util.List<gov.niemplatform.settings.SettingDescriptor> settings() {
        return java.util.List.of();
    }

    /** One line saying what this wire format is, for a palette. */
    default String summary() {
        return "";
    }

    @Override
    void close();
}
