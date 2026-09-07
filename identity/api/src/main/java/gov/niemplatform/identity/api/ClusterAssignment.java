package gov.niemplatform.identity.api;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * What the platform keeps about one resolution (spec §4.5).
 *
 * <p>Exactly three things survive a provider call -- the cluster identifier, the confidence, and
 * the evidence -- plus enough context to attribute the decision. Spec §4.5 forbids persisting an
 * external provider's internal state, and this record is the enforcement of that: whatever a
 * provider returns, only this is written down.
 *
 * <p>Carries no timestamp. That is deliberate: acceptance criterion 6 requires replay to reproduce
 * silver exactly, and an assignment stamped with the wall clock would differ on every replay.
 * When an assignment happened belongs in the lineage record, which is allowed to vary.
 *
 * @param entityType canonical type resolved
 * @param sourceRecordKey the record that produced this assignment
 * @param clusterId cluster the record was assigned to
 * @param providerId which engine decided, so an auditor can tell years later
 * @param confidence how sure that engine was
 * @param evidence why, in terms an auditor can follow
 */
public record ClusterAssignment(
        String entityType,
        String sourceRecordKey,
        ClusterId clusterId,
        String providerId,
        double confidence,
        List<MatchEvidence> evidence) implements Serializable {

    public ClusterAssignment {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(sourceRecordKey, "sourceRecordKey");
        Objects.requireNonNull(clusterId, "clusterId");
        Objects.requireNonNull(providerId, "providerId");
        evidence = List.copyOf(evidence);
        if (confidence < 0.0 || confidence > 1.0 || Double.isNaN(confidence)) {
            throw new IllegalArgumentException("Confidence must be in [0, 1], found " + confidence);
        }
    }

    /** Builds an assignment from a provider's result. */
    public static ClusterAssignment from(
            String entityType, String sourceRecordKey, String providerId, ResolutionResult result) {
        return new ClusterAssignment(entityType, sourceRecordKey, result.clusterId(),
                providerId, result.confidence(), result.evidence());
    }
}
