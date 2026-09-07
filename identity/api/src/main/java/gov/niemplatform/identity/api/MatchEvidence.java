package gov.niemplatform.identity.api;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * Why a resolver reached the decision it did (spec §4.5).
 *
 * <p>Evidence exists so a resolution is explicable to an auditor without reading the resolver's
 * source. "These two records were merged because their normalised surname, given name, and date
 * of birth agreed" is answerable; "the resolver said so, confidence 0.90" is not.
 *
 * <p>It is also the only part of an external provider's output the platform keeps. Spec §4.5
 * forbids persisting a provider's internal state -- only the cluster identifier, the confidence,
 * and the evidence. A provider handing back its own match graph, feature vectors, or internal
 * record identifiers must have them dropped here rather than stored.
 *
 * @param rule identifier of the rule or model that fired, e.g. {@code TIER_1_DRIVER_LICENCE}
 * @param matchedFields the attributes that agreed
 * @param keys the normalised keys compared, carried so a decision can be reproduced
 * @param detail short human-readable explanation
 */
public record MatchEvidence(
        String rule,
        List<String> matchedFields,
        List<ResolutionKey> keys,
        String detail) implements Serializable {

    public MatchEvidence {
        Objects.requireNonNull(rule, "rule");
        matchedFields = List.copyOf(matchedFields);
        keys = List.copyOf(keys);
    }

    /** Evidence that a rule fired against a key. */
    public static MatchEvidence matched(String rule, List<String> fields, ResolutionKey key, String detail) {
        return new MatchEvidence(rule, fields, List.of(key), detail);
    }

    /** Evidence that nothing matched and a cluster was created. */
    public static MatchEvidence newCluster(String detail) {
        return new MatchEvidence("NEW_CLUSTER", List.of(), List.of(), detail);
    }

    /**
     * Rule, field names, and key tiers -- never key values.
     *
     * <p>{@link ResolutionKey#toString()} already redacts, so this is safe by composition, but it
     * is stated because evidence is the type most likely to be logged during an incident.
     */
    @Override
    public String toString() {
        return "MatchEvidence[rule=%s, fields=%s, keys=%s]".formatted(rule, matchedFields, keys);
    }
}
