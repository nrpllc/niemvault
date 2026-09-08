package gov.niemplatform.connectors.api;

/**
 * How records arrive from a source (ADR 0027).
 *
 * <p>A property of the connector, never a second pipeline. Whatever the mode, what happens next is
 * identical: contract-gated hops into canonical. Two ways into canonical would mean one of them is
 * not contract-validated, and given what gets integrated it would be the one carrying criminal
 * history from a state system.
 */
public enum InteractionMode {

    /** The platform reads on its own schedule: a file drop, a directory, a table. */
    POLL,

    /** The source delivers when it has something: a queue, a webhook, a stream. */
    PUSH,

    /**
     * The platform asks a question and waits for the answer.
     *
     * <p>The mode this platform does not get to dictate the shape of. A state or federal system
     * offers what it offers, and the adapter absorbs the difference.
     */
    QUERY
}
