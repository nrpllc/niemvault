package gov.niemplatform.disclosure;

import gov.niemplatform.canonical.meta.CanonicalId;
import gov.niemplatform.canonical.meta.TenantId;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The record of something crossing an agency boundary (§4.8, ADR 0026).
 *
 * <p>This is the artifact handed to counsel, an auditor, or a court. It answers the four questions
 * anyone asks after the fact: who wanted it, under what authority, what did they actually get, and
 * who decided.
 *
 * <h2>It names records; it never contains them</h2>
 *
 * <p>There is no field here that can hold a record value, and that is the point rather than an
 * omission. An audit log containing the data it audits is a second copy of that data, usually with
 * weaker access controls and longer retention than the original — so the log of who saw what becomes
 * the easiest place to see it.
 *
 * <p>So a disclosure names {@link CanonicalId}s. Those are tenant-qualified (ADR 0025), which is what
 * makes them meaningful to the agency reading the log and to the one that minted them, without
 * disclosing anything further. Whoever audits this log needs to know that eleven Person records went
 * to the state on Tuesday under a named statute. They do not need to know who those people are, and
 * in most cases are not entitled to.
 *
 * <h2>A refusal is a disclosure record too</h2>
 *
 * <p>A log that records only what was released cannot answer "did anyone try to get this", which is
 * the first question asked when misuse is suspected. Refusals are recorded with the same weight as
 * grants.
 */
public record DisclosureRecord(
        String disclosureId,
        Instant at,
        TenantId requestedBy,
        String requestingPrincipal,
        TenantId respondedBy,
        String authority,
        String purpose,
        String request,
        DisclosureOutcome outcome,
        String reason,
        List<CanonicalId> disclosed,
        int withheld,
        String decidedBy) implements Serializable {

    public DisclosureRecord {
        Objects.requireNonNull(disclosureId, "disclosureId");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(respondedBy, "respondedBy");
        Objects.requireNonNull(outcome, "outcome");
        disclosed = List.copyOf(disclosed);

        requireStated(requestingPrincipal, "requestingPrincipal",
                "a disclosure to an agency is not a disclosure to a person; the log has to name who "
                        + "asked");
        // Authority and purpose are required because a disclosure without a stated basis cannot be
        // defended later, and the moment to state it is when it is made rather than when it is
        // questioned.
        requireStated(authority, "authority",
                "every disclosure states the authority it was made under, e.g. a statute, an MOU, "
                        + "or a case number");
        requireStated(purpose, "purpose",
                "every disclosure states why it was made; 'because it was asked for' is not a reason "
                        + "anyone can review");
        requireStated(request, "request",
                "the request is recorded as made, so a reviewer can see what was asked rather than "
                        + "only what was returned");
        requireStated(decidedBy, "decidedBy",
                "a decision has a decider, whether a person or a named policy");

        if (outcome != DisclosureOutcome.GRANTED) {
            requireStated(reason, "reason",
                    "a refusal or a partial release states why; a withheld record with no stated "
                            + "reason cannot be appealed or corrected");
        }
        if (withheld < 0) {
            throw new IllegalArgumentException("withheld cannot be negative, was " + withheld);
        }
        if (outcome == DisclosureOutcome.REFUSED && !disclosed.isEmpty()) {
            throw new IllegalArgumentException(
                    "a refused disclosure released " + disclosed.size() + " record(s); that is a "
                            + "partial release, and calling it a refusal would misreport it");
        }
        if (outcome == DisclosureOutcome.PARTIAL && withheld == 0) {
            throw new IllegalArgumentException(
                    "a partial release withheld nothing; either it was granted in full, or the "
                            + "count of what was withheld is missing");
        }
    }

    /** How many records crossed the boundary. */
    public int releasedCount() {
        return disclosed.size();
    }

    private static void requireStated(String value, String field, String why) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("'" + field + "' is required: " + why);
        }
    }

    /**
     * A one-line summary for a person reading a log, with no identities in it.
     *
     * <p>Counts rather than identifiers, because the common case for reading a disclosure log is
     * scanning it, and a screen of canonical ids is unreadable — and printing them by default puts
     * them into terminal scrollback and screenshots.
     */
    @Override
    public String toString() {
        return "%s %s -> %s: %s %d released, %d withheld (%s) under %s"
                .formatted(at, requestedBy, respondedBy, outcome, releasedCount(), withheld,
                        decidedBy, authority);
    }
}
