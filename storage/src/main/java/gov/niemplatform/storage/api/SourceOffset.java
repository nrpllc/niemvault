package gov.niemplatform.storage.api;

import java.io.Serializable;
import java.util.Objects;

/**
 * Where a record sat in its source (spec §4.4): a batch position or a stream offset.
 *
 * <p>Opaque and ordered lexicographically, so a file drop connector can use
 * {@code <file>#<line>} and a Kafka connector can use {@code <partition>:<offset>} without the
 * bronze layer knowing the difference. Spec §4.3 requires transport differences not to leak past
 * the landing boundary, and this is where that rule is most easily broken.
 *
 * <p>Connectors are responsible for zero-padding numeric components so lexicographic order
 * matches source order.
 */
public record SourceOffset(String value) implements Serializable, Comparable<SourceOffset> {

    public SourceOffset {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("A source offset must not be blank");
        }
    }

    public static SourceOffset of(String value) {
        return new SourceOffset(value);
    }

    @Override
    public int compareTo(SourceOffset other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
