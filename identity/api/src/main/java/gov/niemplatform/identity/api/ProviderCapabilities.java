package gov.niemplatform.identity.api;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a resolution provider can and cannot do (spec §4.5).
 *
 * <p>Declared rather than discovered, so a deployment can refuse to start when a provider cannot
 * serve an entity type the mapping needs -- which is better found at configuration time than
 * halfway through a nightly load.
 *
 * @param providerId stable identifier, recorded against every resolution so an auditor can tell
 *     which engine made a decision years later
 * @param supportedEntityTypes canonical types this provider resolves
 * @param probabilistic whether confidence is a real distribution or a fixed value per rule tier
 * @param externallyManaged whether clustering happens outside the platform; when true the
 *     platform still keeps its own index, because cross-domain joins depend on it
 * @param requiredAttributes attributes without which the provider cannot resolve at all
 * @param confidenceTiers the discrete confidence values a non-probabilistic provider emits,
 *     empty when {@code probabilistic}
 */
public record ProviderCapabilities(
        String providerId,
        Set<String> supportedEntityTypes,
        boolean probabilistic,
        boolean externallyManaged,
        Set<String> requiredAttributes,
        List<Double> confidenceTiers) implements Serializable {

    public ProviderCapabilities {
        Objects.requireNonNull(providerId, "providerId");
        supportedEntityTypes = Set.copyOf(supportedEntityTypes);
        requiredAttributes = Set.copyOf(requiredAttributes);
        confidenceTiers = List.copyOf(confidenceTiers);
        if (probabilistic && !confidenceTiers.isEmpty()) {
            throw new IllegalArgumentException(
                    "A probabilistic provider does not have discrete confidence tiers");
        }
    }

    public boolean supports(String entityType) {
        return supportedEntityTypes.contains(entityType);
    }
}
