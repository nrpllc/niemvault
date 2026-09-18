package gov.niemplatform.exchange.api;

/** What became of a submitted document (ADR 0034). */
public enum SubmissionOutcome {

    /** The far side has it and has posted it. */
    ACCEPTED,

    /**
     * The far side refused it, and said why.
     *
     * <p>Not a failure of this platform and not retryable as-is: a rejected submission is usually
     * a data problem an operator has to answer, and resubmitting it unchanged produces the same
     * rejection.
     */
    REJECTED,

    /**
     * The far side took it and will answer later.
     *
     * <p>The normal outcome for a fingerprint submission, and the reason a receipt carries a remote
     * identifier at all. A platform that treated this as success would report a criminal history
     * entry that may still be refused hours later.
     */
    PENDING
}
