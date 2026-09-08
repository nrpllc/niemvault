package gov.niemplatform.connectors.api;

/**
 * Whether records from a source may be kept (ADR 0027).
 *
 * <p>This does not follow from architecture. It follows from law, and it is the reason "everything
 * lands in bronze" cannot be universal: responses from criminal history systems are frequently not
 * retainable, and a landing zone that is append-only and permanent would create an unlawful
 * retention by doing the obviously correct thing.
 */
public enum RetentionPosture {

    /**
     * Records land in bronze and follow the normal path.
     *
     * <p>Replayable, because bronze holds what arrived.
     */
    RETAINED,

    /**
     * Records must not be kept.
     *
     * <p>Used for the request at hand and never landed. The only durable trace is the disclosure
     * record (ADR 0026): that it was asked for, under what authority, and how much came back —
     * never the content. Which is not a coincidence. What you are permitted to keep about a
     * non-retainable response is precisely the fact that you requested it.
     *
     * <p>A source like this <strong>cannot be replayed</strong>, and nothing may pretend otherwise.
     * Replay rests on bronze holding what arrived; where nothing may be held, there is nothing to
     * replay from.
     */
    TRANSIENT;

    /** Whether records from this source reach bronze at all. */
    public boolean landsInBronze() {
        return this == RETAINED;
    }
}
