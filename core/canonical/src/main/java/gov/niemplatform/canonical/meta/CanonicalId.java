package gov.niemplatform.canonical.meta;

import java.io.Serializable;

/**
 * Platform-assigned identity for a canonical entity or association.
 *
 * <p>Assigned by the platform, never by a mapping. For resolvable entities this is the cluster
 * identifier from identity resolution (spec §4.5); for associations and non-resolvable entities
 * it is derived deterministically from the source key so that replay reproduces it exactly
 * (§5, acceptance criterion 6).
 */
public record CanonicalId(String value) implements Serializable, Comparable<CanonicalId> {

    public CanonicalId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A canonical identity must be a non-blank value");
        }
    }

    public static CanonicalId of(String value) {
        return new CanonicalId(value);
    }

    @Override
    public int compareTo(CanonicalId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
