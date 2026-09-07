package gov.niemplatform.runtime.transforms;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds {@link Transform}s from declarative specs (spec §5: mappings are data, not code).
 *
 * <p>The primitive set is small on purpose. Every primitive is another thing a mapping author can
 * get subtly wrong and another thing the platform must keep behaving identically forever, so a
 * new one earns its place by being needed by a real source rather than by being conceivable.
 *
 * <p>Nothing here tolerates a malformed input quietly. A date that does not match its declared
 * pattern raises {@link TransformException}, which the pipeline turns into a contract failure and
 * a quarantined record. Silently emitting null would let a source change format and lose data
 * with nothing to show for it -- exactly the failure spec §4.2 exists to prevent.
 */
public final class TransformFactory {

    private TransformFactory() {}

    /** Transform types this factory understands. */
    public static final Set<String> TYPES = Set.of(
            "copy", "literal", "trim", "upper", "lower", "nullIf", "digitsOnly", "collapseSpace",
            "splitIndex", "regexExtract", "regexReplace", "concat", "coalesce",
            "parseDate", "parseDateTime", "parseInteger", "parseDecimal", "codeMap");

    /**
     * Compiles a spec into a transform.
     *
     * @throws IllegalArgumentException if the spec is malformed. Raised at deploy time, when a
     *     mapping is loaded, rather than on the first record that hits the step.
     */
    public static Transform create(TransformSpec spec) {
        return switch (spec.type()) {
            case "copy" -> unary(spec, Set.of(), (text, s) -> text);
            case "trim" -> unary(spec, Set.of(), (text, s) -> text.trim());
            case "upper" -> unary(spec, Set.of(), (text, s) -> text.toUpperCase(Locale.ROOT));
            case "lower" -> unary(spec, Set.of(), (text, s) -> text.toLowerCase(Locale.ROOT));
            case "collapseSpace" -> unary(spec, Set.of(), (text, s) -> text.trim().replaceAll("\\s+", " "));
            case "digitsOnly" -> unary(spec, Set.of(), (text, s) -> {
                String digits = text.replaceAll("\\D", "");
                return digits.isEmpty() ? null : digits;
            });
            case "literal" -> literal(spec);
            case "nullIf" -> nullIf(spec);
            case "splitIndex" -> splitIndex(spec);
            case "regexExtract" -> regexExtract(spec);
            case "regexReplace" -> regexReplace(spec);
            case "concat" -> concat(spec);
            case "coalesce" -> coalesce(spec);
            case "parseDate" -> parseDate(spec);
            case "parseDateTime" -> parseDateTime(spec);
            case "parseInteger" -> parseInteger(spec);
            case "parseDecimal" -> parseDecimal(spec);
            case "codeMap" -> codeMap(spec);
            default -> throw new IllegalArgumentException(
                    "Unknown transform type '%s' writing '%s'; known types are %s"
                            .formatted(spec.type(), spec.target(), TYPES.stream().sorted().toList()));
        };
    }

    // --- shapes ----------------------------------------------------------

    /** A text-in, value-out transform over a single source field. */
    @FunctionalInterface
    private interface TextFunction extends Serializable {
        Object apply(String text, TransformSpec spec);
    }

    private static Transform unary(TransformSpec spec, Set<String> options, TextFunction function) {
        spec.requireOnlyOptions(options);
        String from = spec.singleFrom();
        return new AbstractTransform(spec) {
            @Override
            public Object evaluate(TransformInput input) {
                return input.text(from).map(text -> function.apply(text, spec())).orElse(null);
            }
        };
    }

    private abstract static class AbstractTransform implements Transform {

        private static final long serialVersionUID = 1L;

        private final TransformSpec spec;

        AbstractTransform(TransformSpec spec) {
            this.spec = spec;
        }

        TransformSpec spec() {
            return spec;
        }

        @Override
        public String target() {
            return spec.target();
        }

        @Override
        public String type() {
            return spec.type();
        }

        @Override
        public String toString() {
            return "%s -> %s(%s)".formatted(spec.target(), spec.type(), spec.from());
        }
    }

    // --- primitives ------------------------------------------------------

    private static Transform literal(TransformSpec spec) {
        spec.requireOnlyOptions(Set.of("value"));
        String value = spec.requiredOption("value");
        return new AbstractTransform(spec) {
            @Override
            public Object evaluate(TransformInput input) {
                return value;
            }
        };
    }

    /**
     * Maps a sentinel to absence.
     *
     * <p>Real CAD exports use {@code UNK}, {@code N/A}, {@code 000-00-0000} and similar to mean
     * "not recorded". Carrying those into canonical would make every downstream consumer learn
     * one agency's sentinels.
     */
    private static Transform nullIf(TransformSpec spec) {
        spec.requireOnlyOptions(Set.of("values", "ignoreCase"));
        List<String> sentinels = Arrays.stream(spec.requiredOption("values").split(",", -1))
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
        boolean ignoreCase = Boolean.parseBoolean(spec.optionOr("ignoreCase", "true"));
        String from = spec.singleFrom();
        return new AbstractTransform(spec) {
            @Override
            public Object evaluate(TransformInput input) {
                return input.text(from)
                        .filter(text -> sentinels.stream().noneMatch(sentinel ->
                                ignoreCase ? sentinel.equalsIgnoreCase(text.trim()) : sentinel.equals(text.trim())))
                        .orElse(null);
            }
        };
    }

    /** Splits on a literal delimiter and takes one part. Built for {@code LAST, FIRST M}. */
    private static Transform splitIndex(TransformSpec spec) {
        spec.requireOnlyOptions(Set.of("delimiter", "index", "trim"));
        String delimiter = spec.requiredOption("delimiter");
        int index = Integer.parseInt(spec.requiredOption("index"));
        boolean trim = Boolean.parseBoolean(spec.optionOr("trim", "true"));
        String from = spec.singleFrom();
        if (index < 0) {
            throw new IllegalArgumentException(
                    "splitIndex writing '%s' needs a non-negative index, found %d".formatted(spec.target(), index));
        }
        return new AbstractTransform(spec) {
            @Override
            public Object evaluate(TransformInput input) {
                Optional<String> text = input.text(from);
                if (text.isEmpty()) {
                    return null;
                }
                String[] parts = text.get().split(Pattern.quote(delimiter), -1);
                if (index >= parts.length) {
                    // Not an error: "DOE" with no comma has no given name, which is data.
                    return null;
                }
                String part = trim ? parts[index].trim() : parts[index];
                return part.isEmpty() ? null : part;
            }
        };
    }

    private static Transform regexExtract(TransformSpec spec) {
        spec.requireOnlyOptions(Set.of("pattern", "group"));
        Pattern pattern = Pattern.compile(spec.requiredOption("pattern"));
        int group = Integer.parseInt(spec.optionOr("group", "1"));
        String from = spec.singleFrom();
        return new AbstractTransform(spec) {
            @Override
            public Object evaluate(TransformInput input) {
                Optional<String> text = input.text(from);
                if (text.isEmpty()) {
                    return null;
                }
                Matcher matcher = pattern.matcher(text.get());
                if (!matcher.find()) {
                    return null;
                }
                String extracted = matcher.group(group);
                return extracted == null || extracted.isEmpty() ? null : extracted;
            }
        };
    }

    private static Transform regexReplace(TransformSpec spec) {
        spec.requireOnlyOptions(Set.of("pattern", "replacement"));
        Pattern pattern = Pattern.compile(spec.requiredOption("pattern"));
        String replacement = spec.optionOr("replacement", "");
        String from = spec.singleFrom();
        return new AbstractTransform(spec) {
            @Override
            public Object evaluate(TransformInput input) {
                return input.text(from)
                        .map(text -> pattern.matcher(text).replaceAll(replacement))
                        .filter(text -> !text.isEmpty())
                        .orElse(null);
            }
        };
    }

    private static Transform concat(TransformSpec spec) {
        spec.requireOnlyOptions(Set.of("separator", "skipMissing"));
        String separator = spec.optionOr("separator", "");
        boolean skipMissing = Boolean.parseBoolean(spec.optionOr("skipMissing", "true"));
        List<String> from = spec.from();
        if (from.isEmpty()) {
            throw new IllegalArgumentException(
                    "concat writing '%s' needs at least one 'from' field".formatted(spec.target()));
        }
        return new AbstractTransform(spec) {
            @Override
            public Object evaluate(TransformInput input) {
                List<String> parts = new ArrayList<>(from.size());
                for (String field : from) {
                    Optional<String> value = input.text(field);
                    if (value.isPresent()) {
                        parts.add(value.get());
                    } else if (!skipMissing) {
                        return null;
                    }
                }
                return parts.isEmpty() ? null : String.join(separator, parts);
            }
        };
    }

    /** First non-absent of several fields. */
    private static Transform coalesce(TransformSpec spec) {
        spec.requireOnlyOptions(Set.of());
        List<String> from = spec.from();
        if (from.size() < 2) {
            throw new IllegalArgumentException(
                    "coalesce writing '%s' needs at least two 'from' fields".formatted(spec.target()));
        }
        return new AbstractTransform(spec) {
            @Override
            public Object evaluate(TransformInput input) {
                for (String field : from) {
                    Object value = input.value(field);
                    if (value != null && !value.toString().isBlank()) {
                        return value;
                    }
                }
                return null;
            }
        };
    }

    /**
     * Parses a date against exactly one declared pattern.
     *
     * <p>One pattern, deliberately. Accepting a list of patterns would make the mapping tolerant
     * of a source silently changing its date format -- which is the precise failure the platform
     * exists to catch, not to absorb.
     */
    private static Transform parseDate(TransformSpec spec) {
        spec.requireOnlyOptions(Set.of("pattern"));
        String pattern = spec.requiredOption("pattern");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
        String from = spec.singleFrom();
        return new AbstractTransform(spec) {
            @Override
            public Object evaluate(TransformInput input) {
                Optional<String> text = input.text(from);
                if (text.isEmpty()) {
                    return null;
                }
                try {
                    return LocalDate.parse(text.get().trim(), formatter);
                } catch (DateTimeParseException e) {
                    throw new TransformException(type(), target(), from,
                            "a date matching " + pattern, shapeOf(text.get()));
                }
            }
        };
    }

    private static Transform parseDateTime(TransformSpec spec) {
        spec.requireOnlyOptions(Set.of("pattern", "zone"));
        String pattern = spec.requiredOption("pattern");
        ZoneId zone = ZoneId.of(spec.requiredOption("zone"));
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
        String from = spec.singleFrom();
        return new AbstractTransform(spec) {
            @Override
            public Object evaluate(TransformInput input) {
                Optional<String> text = input.text(from);
                if (text.isEmpty()) {
                    return null;
                }
                try {
                    LocalDateTime local = LocalDateTime.parse(text.get().trim(), formatter);
                    // Zone is declared by the mapping, never inferred: a CAD export writes local
                    // wall-clock time with no offset, and guessing it would shift every incident.
                    return local.atZone(zone).toInstant();
                } catch (DateTimeParseException e) {
                    throw new TransformException(type(), target(), from,
                            "a date-time matching " + pattern, shapeOf(text.get()));
                }
            }
        };
    }

    private static Transform parseInteger(TransformSpec spec) {
        spec.requireOnlyOptions(Set.of());
        String from = spec.singleFrom();
        return new AbstractTransform(spec) {
            @Override
            public Object evaluate(TransformInput input) {
                Optional<String> text = input.text(from);
                if (text.isEmpty()) {
                    return null;
                }
                try {
                    return Long.valueOf(text.get().trim());
                } catch (NumberFormatException e) {
                    throw new TransformException(type(), target(), from,
                            "a whole number", shapeOf(text.get()));
                }
            }
        };
    }

    private static Transform parseDecimal(TransformSpec spec) {
        spec.requireOnlyOptions(Set.of());
        String from = spec.singleFrom();
        return new AbstractTransform(spec) {
            @Override
            public Object evaluate(TransformInput input) {
                Optional<String> text = input.text(from);
                if (text.isEmpty()) {
                    return null;
                }
                try {
                    return new BigDecimal(text.get().trim());
                } catch (NumberFormatException e) {
                    throw new TransformException(type(), target(), from,
                            "a decimal number", shapeOf(text.get()));
                }
            }
        };
    }

    /**
     * Maps an agency's vocabulary onto a canonical code list.
     *
     * <p>An unmapped value is a {@link TransformException} rather than a pass-through, because a
     * new dispatch code appearing in a source is a real change an agency needs to see, not
     * something to forward into canonical and discover later in the graph. A mapping that wants
     * a catch-all declares {@code default}.
     */
    private static Transform codeMap(TransformSpec spec) {
        spec.requireOnlyOptions(Set.of("map", "default", "ignoreCase"));
        Map<String, String> mapping = parseMapping(spec);
        String fallback = spec.option("default").orElse(null);
        boolean ignoreCase = Boolean.parseBoolean(spec.optionOr("ignoreCase", "true"));
        String from = spec.singleFrom();

        Map<String, String> lookup = new LinkedHashMap<>();
        mapping.forEach((key, value) -> lookup.put(ignoreCase ? key.toUpperCase(Locale.ROOT) : key, value));

        return new AbstractTransform(spec) {
            @Override
            public Object evaluate(TransformInput input) {
                Optional<String> text = input.text(from);
                if (text.isEmpty()) {
                    return null;
                }
                String key = text.get().trim();
                String mapped = lookup.get(ignoreCase ? key.toUpperCase(Locale.ROOT) : key);
                if (mapped != null) {
                    return mapped;
                }
                if (fallback != null) {
                    return fallback;
                }
                throw new TransformException(type(), target(), from,
                        "one of " + lookup.keySet(), shapeOf(key));
            }
        };
    }

    private static Map<String, String> parseMapping(TransformSpec spec) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String entry : spec.requiredOption("map").split(",", -1)) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException(
                        "codeMap writing '%s' has a malformed entry '%s'; expected 'source=canonical'"
                                .formatted(spec.target(), trimmed));
            }
            mapping.put(trimmed.substring(0, separator).trim(), trimmed.substring(separator + 1).trim());
        }
        if (mapping.isEmpty()) {
            throw new IllegalArgumentException(
                    "codeMap writing '%s' has an empty 'map'".formatted(spec.target()));
        }
        return mapping;
    }

    /**
     * Redacted description of an offending value, for the exception message.
     *
     * <p>Digits become {@code #} and letters {@code A}, matching {@code ValueShape}. Transform
     * failures end up in contract violation events, so this must not carry the value. ADR 0015.
     */
    static String shapeOf(String text) {
        int limit = Math.min(text.length(), 32);
        StringBuilder shape = new StringBuilder(limit);
        for (int i = 0; i < limit; i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c)) {
                shape.append('#');
            } else if (Character.isLetter(c)) {
                shape.append('A');
            } else if (Character.isWhitespace(c)) {
                shape.append('_');
            } else {
                shape.append(c);
            }
        }
        if (text.length() > limit) {
            shape.append("...");
        }
        return shape.toString();
    }
}
