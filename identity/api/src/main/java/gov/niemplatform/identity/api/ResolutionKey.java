package gov.niemplatform.identity.api;

import java.io.Serializable;
import java.util.Objects;

/**
 * A normalised value a resolver can match on, tagged with the rule tier that produced it.
 *
 * <p>Keys are what the platform's own index is keyed by, so they must be normalised before they
 * get here -- {@code K4471902} and {@code k447-1902} are the same licence and must produce the
 * same key, or two records for one person quietly become two clusters.
 *
 * <p>The tier participates in identity: a driver licence number and a Social Security Number that
 * happened to share digits must not be treated as the same key.
 *
 * @param tier rule tier that produced the key, e.g. {@code DL} or {@code NAME_DOB}
 * @param value the normalised value
 */
public record ResolutionKey(String tier, String value) implements Serializable, Comparable<ResolutionKey> {

    public ResolutionKey {
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(value, "value");
        if (tier.isBlank() || value.isBlank()) {
            throw new IllegalArgumentException("A resolution key needs both a tier and a value");
        }
    }

    public static ResolutionKey of(String tier, String value) {
        return new ResolutionKey(tier, value);
    }

    @Override
    public int compareTo(ResolutionKey other) {
        int byTier = tier.compareTo(other.tier);
        return byTier != 0 ? byTier : value.compareTo(other.value);
    }

    /**
     * Tier and the value's length -- never the value itself.
     *
     * <p>Keys are built from licence numbers, Social Security Numbers, and names. Same obligation
     * as {@code Record.toString()}; see ADR 0015.
     */
    @Override
    public String toString() {
        return tier + "(len=" + value.length() + ")";
    }
}
