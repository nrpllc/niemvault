package gov.niemplatform.identity.api;

import java.util.Objects;

/**
 * Wraps any resolution provider so the platform keeps its own index of what was decided.
 *
 * <p>This class is the enforcement of the two rules spec §4.5 states about external providers:
 *
 * <ul>
 *   <li><strong>The platform always maintains its own thin index of cluster identifiers</strong>,
 *       even when an external provider does the resolution work. A deployment running Senzing
 *       still records assignments here, because a Senzing identifier means nothing to the graph
 *       projection and nothing to a second domain module. Cross-domain joins depend on it.
 *   <li><strong>An external provider's internal state is never persisted.</strong> Whatever the
 *       provider returns, only the cluster identifier, the confidence, and the evidence are
 *       written down -- which is precisely what {@link ClusterAssignment} holds and all it holds.
 * </ul>
 *
 * <p>Every provider should be wrapped in one of these at configuration time. A provider used
 * unwrapped will resolve correctly and leave the platform unable to join across domains, which is
 * a failure that will not show up until the second domain module ships.
 */
public final class IndexedResolutionProvider implements ResolutionProvider {

    private final ResolutionProvider delegate;
    private final ClusterIndex index;

    public IndexedResolutionProvider(ResolutionProvider delegate, ClusterIndex index) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.index = Objects.requireNonNull(index, "index");
    }

    @Override
    public ResolutionResult resolve(EntityAttributes attributes) {
        ResolutionResult result = delegate.resolve(attributes);
        index.record(ClusterAssignment.from(
                attributes.entityType(),
                attributes.sourceRecordKey(),
                delegate.capabilities().providerId(),
                result));
        return result;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return delegate.capabilities();
    }

    /** The platform's index, for the projection divergence check and for the operator CLI. */
    public ClusterIndex index() {
        return index;
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public String toString() {
        return "IndexedResolutionProvider[" + delegate.capabilities().providerId() + "]";
    }
}
