package gov.niemplatform.runtime.transforms;

import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A declarative transformation step, exactly as it appears in a mapping artifact.
 *
 * <p>This is the "mappings are data, not code" boundary of spec §5. A mapping artifact contains
 * these; {@link TransformFactory} turns them into {@link Transform}s at deploy time. An agency
 * changing a mapping edits a spec, never a class.
 *
 * @param target field this step writes
 * @param type transform type, e.g. {@code copy} or {@code parseDateTime}
 * @param from input fields, in order; most transforms take exactly one
 * @param options transform-specific options
 */
public record TransformSpec(
        String target,
        String type,
        List<String> from,
        Map<String, String> options) implements Serializable {

    public TransformSpec {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(type, "type");
        from = List.copyOf(from);
        options = Map.copyOf(options);
        if (target.isBlank()) {
            throw new IllegalArgumentException("A transform step must name the field it writes");
        }
    }

    public static TransformSpec of(String target, String type, String from) {
        return new TransformSpec(target, type, List.of(from), Map.of());
    }

    public static TransformSpec of(String target, String type, String from, Map<String, String> options) {
        return new TransformSpec(target, type, List.of(from), options);
    }

    /** The single input field, for transforms that take exactly one. */
    public String singleFrom() {
        if (from.size() != 1) {
            throw new IllegalArgumentException(
                    "Transform '%s' writing '%s' takes exactly one 'from' field, found %d"
                            .formatted(type, target, from.size()));
        }
        return from.getFirst();
    }

    public Optional<String> option(String key) {
        return Optional.ofNullable(options.get(key)).filter(value -> !value.isEmpty());
    }

    /** A required option, rejected loudly at compile time rather than on the first record. */
    public String requiredOption(String key) {
        return option(key).orElseThrow(() -> new IllegalArgumentException(
                "Transform '%s' writing '%s' requires option '%s'".formatted(type, target, key)));
    }

    public String optionOr(String key, String fallback) {
        return option(key).orElse(fallback);
    }

    /**
     * Rejects options this transform type does not recognise.
     *
     * <p>Same reasoning as everywhere else config is read (ADR 0010): a misspelled option that is
     * silently ignored leaves a transform running on a default nobody chose, and the mapping still
     * looks correct on the page.
     */
    public void requireOnlyOptions(Set<String> recognised) {
        Set<String> unknown = new LinkedHashSet<>(options.keySet());
        unknown.removeAll(recognised);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "Transform '%s' writing '%s' has unrecognised option(s) %s; it accepts %s"
                            .formatted(type, target, unknown, recognised.stream().sorted().toList()));
        }
    }
}
