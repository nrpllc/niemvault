package gov.niemplatform.identity.api;

/**
 * Resolves entity attributes to a cluster (spec §4.5).
 *
 * <p>Pluggable so agencies with existing investment -- Senzing, IBM entity analytics -- put theirs
 * behind this interface rather than being asked to abandon it. The bundled default in
 * {@code identity/internal} implements the same contract with a different engine.
 *
 * <p>Two rules constrain every implementation, and both come straight from spec §4.5:
 *
 * <ul>
 *   <li><strong>The platform always keeps its own index of cluster identifiers</strong>, even when
 *       an external provider does the resolution work. Without it, cross-domain joins in the graph
 *       are impossible. A provider that returns only its own internal identifiers has not
 *       satisfied this interface.
 *   <li><strong>An external provider's internal state is never persisted.</strong> Only the
 *       cluster identifier, the confidence, and the evidence survive a call. A provider handing
 *       back its match graph, feature vectors, or internal record identifiers must have them
 *       dropped rather than stored.
 * </ul>
 *
 * <p>Implementations must be thread-safe: resolution runs inside Flink operators, of which there
 * may be many.
 */
public interface ResolutionProvider extends AutoCloseable {

    /**
     * Resolves one entity.
     *
     * @throws UnsupportedEntityTypeException if the provider does not handle this entity type;
     *     a deployment should have caught that from {@link #capabilities()} at configuration time
     */
    ResolutionResult resolve(EntityAttributes attributes);

    /** What this provider can do, declared up front. */
    ProviderCapabilities capabilities();

    @Override
    default void close() {
        // Most providers hold nothing open; those that do override this.
    }

    /** A provider was asked for an entity type it does not handle. */
    class UnsupportedEntityTypeException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String providerId;
        private final String entityType;

        public UnsupportedEntityTypeException(String providerId, String entityType) {
            super("Resolution provider '%s' does not resolve entity type '%s'"
                    .formatted(providerId, entityType));
            this.providerId = providerId;
            this.entityType = entityType;
        }

        public String providerId() {
            return providerId;
        }

        public String entityType() {
            return entityType;
        }
    }
}
