package gov.niemplatform.storage.api;

import java.io.Serializable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Content hash of a landed payload (spec §4.4).
 *
 * <p>Carries its algorithm rather than assuming one, so a deployment that must move off SHA-256
 * can do so without every stored hash becoming ambiguous.
 *
 * <p>Used to detect duplicate landing without re-reading payloads, and to prove during an audit
 * that what is in bronze is what was received.
 */
public record ContentHash(String algorithm, String value) implements Serializable, Comparable<ContentHash> {

    public ContentHash {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("A content hash must have a value");
        }
    }

    /** Hashes a payload with SHA-256. */
    public static ContentHash sha256(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new ContentHash("sha256", HexFormat.of().formatHex(digest.digest(payload)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; its absence is not a recoverable condition.
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
    }

    /** Parses the stored form, {@code algorithm:value}. */
    public static ContentHash parse(String text) {
        int separator = text.indexOf(':');
        if (separator < 1 || separator == text.length() - 1) {
            throw new IllegalArgumentException(
                    "A content hash must be written as 'algorithm:value', found '" + text + "'");
        }
        return new ContentHash(text.substring(0, separator), text.substring(separator + 1));
    }

    @Override
    public int compareTo(ContentHash other) {
        int byAlgorithm = algorithm.compareTo(other.algorithm);
        return byAlgorithm != 0 ? byAlgorithm : value.compareTo(other.value);
    }

    /** Stored form, {@code algorithm:value}. */
    @Override
    public String toString() {
        return algorithm + ":" + value;
    }
}
