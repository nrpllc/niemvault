package gov.niemplatform.projections.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What a run did, as a whole: how many records it landed, what became of each, and why anything was
 * held back (ADR 0035, operational state).
 *
 * <p>Handed to every projection when a run finishes, and recorded by the ones that keep operational
 * state -- the ODS. Until this existed the completeness line and the list of contract violations
 * went to a terminal and nowhere else, so the only record that a vendor's format had drifted was
 * the scrollback of whoever ran the job. A repository showing "3 incidents arrived" with no way to
 * see that four rows were refused is showing a partial picture as a whole one.
 *
 * <p>Not a change set and not gold: nothing here is a canonical record, and a rebuild never
 * reproduces it. It is what happened, appended.
 *
 * @param balanced whether landed x hops = produced + quarantined + skipped; false is a breach
 * @param violations every contract violation, described by shape only (ADR 0015) -- never a value
 */
public record RunReport(
        String runId,
        String sourceId,
        String mapping,
        Instant startedAt,
        Instant finishedAt,
        long landed,
        long produced,
        long quarantined,
        long skipped,
        boolean balanced,
        List<Violation> violations) {

    public RunReport {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(mapping, "mapping");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        violations = List.copyOf(violations);
    }

    /**
     * One record held back by a contract.
     *
     * @param detail the failures as the platform prints them -- field, rule, expected, and the
     *     <em>shape</em> of what was found -- which is what makes it safe to store and show
     */
    public record Violation(String hop, String direction, String recordType, String detail) {

        public Violation {
            Objects.requireNonNull(detail, "detail");
        }
    }
}
