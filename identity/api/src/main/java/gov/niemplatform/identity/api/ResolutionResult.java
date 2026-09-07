package gov.niemplatform.identity.api;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * What a resolver decided (spec §4.5).
 *
 * <p>Exactly three things are persisted from any resolution -- the cluster identifier, the
 * confidence, and the evidence. Spec §4.5 forbids persisting an external provider's internal
 * state, so this record is deliberately the whole of what survives a provider call.
 *
 * @param clusterId the cluster this entity belongs to
 * @param confidence how sure the resolver is, in [0, 1]
 * @param evidence why, in terms an auditor can follow
 * @param newCluster whether this resolution created the cluster rather than joining one
 */
public record ResolutionResult(
        ClusterId clusterId,
        double confidence,
        List<MatchEvidence> evidence,
        boolean newCluster) implements Serializable {

    public ResolutionResult {
        Objects.requireNonNull(clusterId, "clusterId");
        evidence = List.copyOf(evidence);
        if (confidence < 0.0 || confidence > 1.0 || Double.isNaN(confidence)) {
            throw new IllegalArgumentException("Confidence must be in [0, 1], found " + confidence);
        }
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException(
                    "A resolution must carry evidence; an unexplainable decision is not auditable");
        }
    }

    /** The entity joined an existing cluster. */
    public static ResolutionResult matched(ClusterId clusterId, double confidence, List<MatchEvidence> evidence) {
        return new ResolutionResult(clusterId, confidence, evidence, false);
    }

    /**
     * Nothing matched, so a cluster was created for this entity alone.
     *
     * <p>Confidence is 1.0 and that is not a fudge: the resolver is certain it created a new
     * cluster. It says nothing about whether a better resolver would have found a match.
     */
    public static ResolutionResult created(ClusterId clusterId, List<MatchEvidence> evidence) {
        return new ResolutionResult(clusterId, 1.0, evidence, true);
    }
}
