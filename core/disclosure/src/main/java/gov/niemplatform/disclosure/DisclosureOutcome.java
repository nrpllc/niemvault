package gov.niemplatform.disclosure;

/** What happened to a request that crossed an agency boundary. */
public enum DisclosureOutcome {

    /** Everything asked for was released. */
    GRANTED,

    /**
     * Some records were released and some withheld.
     *
     * <p>Distinct from GRANTED on purpose. An agency that receives a partial release and believes it
     * received everything draws conclusions from an absence that was a decision, not a fact about
     * the world.
     */
    PARTIAL,

    /** Nothing was released. Recorded with the same weight as a release. */
    REFUSED
}
