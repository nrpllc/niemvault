package gov.niemplatform.observability;

/** How urgently an observability event needs a human. */
public enum Severity {
    /** Expected, worth recording. */
    INFO,
    /**
     * Something is drifting. Nothing has failed, which is exactly why it matters -- spec §4.7:
     * the failure this platform exists to prevent is silent corruption, not visible crashes.
     */
    WARNING,
    /** A record was quarantined, or a projection is known to be wrong. */
    ERROR
}
