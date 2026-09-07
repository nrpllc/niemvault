package gov.niemplatform.identity.api;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory cluster index.
 *
 * <p>Sufficient for a single-node run and for tests. A deployment that must survive a restart
 * needs a durable implementation of {@link ClusterIndex} -- cluster identifiers appear in gold and
 * in every downstream join, so losing them means re-resolving history and invalidating every
 * identifier an investigator has seen.
 *
 * <p>Thread-safe: resolution runs inside Flink operators, of which there may be many.
 */
public final class InMemoryClusterIndex implements ClusterIndex {

    private final gov.niemplatform.canonical.meta.TenantId tenant;

    private final Map<String, Map<ResolutionKey, ClusterId>> keysByType = new ConcurrentHashMap<>();
    private final Map<String, Map<String, ClusterAssignment>> assignmentsByType = new ConcurrentHashMap<>();
    private final Map<String, Set<ClusterId>> clustersByType = new ConcurrentHashMap<>();

    /**
     * An index for one agency.
     *
     * <p>Required rather than defaulted. A deployment that hosts one tenant still names it: "the
     * tenant is implied" is how a shared deployment becomes commingled the first time somebody adds
     * a second agency (ADR 0025).
     */
    public InMemoryClusterIndex(gov.niemplatform.canonical.meta.TenantId tenant) {
        this.tenant = Objects.requireNonNull(tenant, "tenant");
    }

    @Override
    public gov.niemplatform.canonical.meta.TenantId tenant() {
        return tenant;
    }

    @Override
    public Optional<ClusterId> find(String entityType, ResolutionKey key) {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(keys(entityType).get(key));
    }

    @Override
    public List<ResolutionKey> link(String entityType, ClusterId clusterId, Collection<ResolutionKey> keys) {
        Objects.requireNonNull(clusterId, "clusterId");
        Map<ResolutionKey, ClusterId> index = keys(entityType);
        List<ResolutionKey> conflicting = new java.util.ArrayList<>();

        for (ResolutionKey key : keys) {
            ClusterId existing = index.putIfAbsent(key, clusterId);
            if (existing != null && !existing.equals(clusterId)) {
                // Left pointing where it was. Re-pointing here would merge two clusters that a
                // stronger rule kept apart, which is the failure direction that matters least.
                conflicting.add(key);
            }
        }
        clusters(entityType).add(clusterId);
        return List.copyOf(conflicting);
    }

    @Override
    public void record(ClusterAssignment assignment) {
        Objects.requireNonNull(assignment, "assignment");
        assignmentsByType
                .computeIfAbsent(assignment.entityType(), type -> new ConcurrentHashMap<>())
                .put(assignment.sourceRecordKey(), assignment);
        clusters(assignment.entityType()).add(assignment.clusterId());
    }

    @Override
    public Optional<ClusterAssignment> assignment(String entityType, String sourceRecordKey) {
        return Optional.ofNullable(
                assignmentsByType.getOrDefault(entityType, Map.of()).get(sourceRecordKey));
    }

    @Override
    public Set<ClusterId> clusters(String entityType) {
        return clustersByType.computeIfAbsent(entityType, type -> ConcurrentHashMap.newKeySet());
    }

    private Map<ResolutionKey, ClusterId> keys(String entityType) {
        return keysByType.computeIfAbsent(entityType, type -> new ConcurrentHashMap<>());
    }

    /** Every key currently indexed for an entity type, for diagnostics. */
    public Set<ResolutionKey> indexedKeys(String entityType) {
        return new LinkedHashSet<>(keys(entityType).keySet());
    }
}
