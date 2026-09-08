package gov.niemplatform.observability;

/**
 * The event taxonomy of spec §4.7.
 *
 * <p>Closed on purpose. A sink that renders these can render every event the platform emits,
 * and a new kind of event is a deliberate addition rather than a new log string nobody indexes.
 */
public enum EventType {
    /** A record failed its hop contract on the way in or out. */
    CONTRACT_VIOLATION,
    /** A source's field shape or cardinality deviated from its rolling baseline. */
    SOURCE_DRIFT,
    /** A source's freshness fell behind its declared SLA. */
    PIPELINE_LAG,
    /** The confidence distribution of identity resolution shifted. */
    RESOLUTION_ANOMALY,
    /** A projection's counts diverged from what silver implies they should be. */
    PROJECTION_DIVERGENCE,
    /**
     * Records landed and were not accounted for anywhere.
     *
     * <p>Distinct from a contract violation, which reports a record the platform knowingly
     * rejected. This one reports arithmetic that does not balance -- the platform cannot say what
     * became of data an agency gave it.
     */
    COMPLETENESS_BREACH
}
