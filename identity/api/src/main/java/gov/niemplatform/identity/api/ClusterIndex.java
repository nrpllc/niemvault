package gov.niemplatform.identity.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The platform's own thin index of cluster identifiers (spec §4.5).
 *
 * <p>Maintained <strong>always</strong>, including when an external provider does the resolution
 * work. Spec §4.5 is explicit about why: without it, cross-domain joins in the graph are
 * impossible. A deployment running Senzing still keeps this, because a Senzing identifier means
 * nothing to the projection layer and nothing to a second domain module.
 *
 * <p>Thin on purpose. It holds keys, cluster identifiers, and the assignments that produced them
 * -- not attributes, not match graphs, not a provider's working state. Anything richer would be
 * a second entity resolution engine maintained by accident.
 */
public interface ClusterIndex {

    /**
     * The agency whose clusters this index holds (ADR 0025).
     *
     * <p>An index is scoped to one tenant. A shared deployment holds one per agency, and a resolver
     * refuses an index belonging to somebody else — a tenant boundary that exists only in a path
     * string is not a boundary.
     */
    gov.niemplatform.canonical.meta.TenantId tenant();

    /** The cluster a key currently points at, if any. */
    Optional<ClusterId> find(String entityType, ResolutionKey key);

    /**
     * Points keys at a cluster.
     *
     * <p>A key already pointing at a <em>different</em> cluster is left alone rather than
     * repointed. Re-pointing would silently merge two clusters that a stronger rule had kept
     * apart, and in criminal justice a false merge is far worse than a missed one (ADR 0014).
     * The keys that were not linked are returned so the caller can see the conflict.
     *
     * @return keys that were left pointing elsewhere, empty when all were linked
     */
    List<ResolutionKey> link(String entityType, ClusterId clusterId, Collection<ResolutionKey> keys);

    /** Records what a provider decided, keeping only what spec §4.5 permits. */
    void record(ClusterAssignment assignment);

    /** The assignment for a source record, if it has been resolved. */
    Optional<ClusterAssignment> assignment(String entityType, String sourceRecordKey);

    /** Every cluster known for an entity type. Backs the projection divergence check (§4.7). */
    Set<ClusterId> clusters(String entityType);

    /** How many clusters exist for an entity type. */
    default long clusterCount(String entityType) {
        return clusters(entityType).size();
    }
}
