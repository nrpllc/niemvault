package gov.niemplatform.identity.api;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Identifier for a resolved entity cluster (spec §4.5).
 *
 * <p><strong>Derived, never generated.</strong> Acceptance criterion 6 requires replay to
 * reproduce silver exactly, and a randomly generated cluster identifier would make every replay
 * differ from the original -- invisibly, until something joined on it. So a cluster's identity is
 * a function of the resolution key that first created it, which means the same bronze replayed in
 * the same order yields the same clusters.
 *
 * <p>This is also the identifier the platform keeps for itself even when an external provider did
 * the resolution work. Spec §4.5 is explicit that without the platform's own index, cross-domain
 * joins in the graph are impossible.
 */
public record ClusterId(String value) implements Serializable, Comparable<ClusterId> {

    public ClusterId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("A cluster identifier must not be blank");
        }
    }

    /**
     * Derives a stable identifier from the key that seeded the cluster.
     *
     * <p>The entity type participates so a Person and an Incident that happen to share a key value
     * cannot collide.
     */
    public static ClusterId seededBy(
            gov.niemplatform.canonical.meta.TenantId tenant, String entityType, ResolutionKey key) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(key, "key");
        // The tenant participates (ADR 0025). Without it, two agencies sharing a deployment and
        // holding the same driver licence number derive the same identifier and their records
        // merge -- commingling of criminal justice data between agencies, arriving silently as a
        // property of a hash function. Linking a person across agencies is a deliberate,
        // attributable assertion; it is never a coincidence of hashing.
        String material = tenant.value() + " " + entityType + " " + key.tier() + " " + key.value();
        return new ClusterId(
                tenant.value() + "/" + entityType.toLowerCase(Locale.ROOT) + ":" + hash(material));
    }

    /** Adopts an identifier the platform already holds, e.g. read back from the index. */
    public static ClusterId of(String value) {
        return new ClusterId(value);
    }

    private static String hash(String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
    }

    @Override
    public int compareTo(ClusterId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
