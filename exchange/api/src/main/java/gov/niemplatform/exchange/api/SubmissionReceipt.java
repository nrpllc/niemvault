package gov.niemplatform.exchange.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What the far side said about one submitted document (ADR 0034).
 *
 * <p>The thing a projection has no need of and a submission cannot work without. A graph write
 * either happened or threw; a repository submission is accepted, rejected, or held for an answer
 * that arrives later, and the identifier it is answered against has to survive until it does.
 *
 * @param documentId the platform's own identity for the document, derived from the root record
 * @param outcome what happened
 * @param remoteId the identifier the far side answers against -- a transaction control number for a
 *     fingerprint submission -- or null where none was issued. Without it an asynchronous
 *     rejection cannot be matched to what it rejects.
 * @param detail what the far side said, for an operator reading a rejection
 * @param at when this was recorded
 */
public record SubmissionReceipt(
        String documentId,
        SubmissionOutcome outcome,
        String remoteId,
        String detail,
        Instant at) {

    public SubmissionReceipt {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(at, "at");
    }

    /** The identifier the far side answers against, where it issued one. */
    public Optional<String> remoteIdentifier() {
        return Optional.ofNullable(remoteId).filter(id -> !id.isBlank());
    }

    public static SubmissionReceipt accepted(String documentId, String remoteId, Instant at) {
        return new SubmissionReceipt(documentId, SubmissionOutcome.ACCEPTED, remoteId, null, at);
    }

    public static SubmissionReceipt rejected(String documentId, String detail, Instant at) {
        return new SubmissionReceipt(documentId, SubmissionOutcome.REJECTED, null, detail, at);
    }

    public static SubmissionReceipt pending(String documentId, String remoteId, Instant at) {
        return new SubmissionReceipt(documentId, SubmissionOutcome.PENDING, remoteId, null, at);
    }
}
