package gov.niemplatform.observability;

/**
 * Which side of a hop a validation applies to.
 *
 * <p>Spec §4.2 requires validation on <em>both</em> the input and the output of every hop.
 * Input validation catches a source that changed; output validation catches a mapping that is
 * wrong. Only running one of them leaves half the failure surface unwatched.
 *
 * <p>Lives here rather than in {@code core:contracts} because it is part of the event
 * vocabulary, and {@code contracts} depends on this module rather than the reverse.
 */
public enum Direction {
    /** Validating what arrived at the hop. */
    INPUT,
    /** Validating what the hop produced. */
    OUTPUT
}
