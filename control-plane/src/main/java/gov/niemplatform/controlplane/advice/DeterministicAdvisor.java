package gov.niemplatform.controlplane.advice;

import gov.niemplatform.canonical.meta.CanonicalFieldDescriptor;
import gov.niemplatform.canonical.meta.FieldType;
import gov.niemplatform.observability.ValueShape;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Proposes mappings without a model, a network, or any configuration (ADR 0023).
 *
 * <p>This is the floor, not a stopgap. In a fully air-gapped deployment with no inference endpoint
 * configured it is the only advisor that will ever run, so it has to be worth using on its own.
 *
 * <h2>How it decides</h2>
 *
 * <p>Three signals, in order of how much they are trusted:
 *
 * <ol>
 *   <li><b>The shape of the data.</b> A column that is consistently two digits, a slash, two digits,
 *       a slash and four digits is a date in {@code MM/dd/yyyy}, whatever it is called. This is the
 *       strongest signal available and the reason shapes are worth carrying (ADR 0023) — it picks
 *       both the transform and its pattern, which a name never can.
 *   <li><b>The name.</b> {@code DOB} and {@code birthDate} are the same thing; so are {@code ADDR}
 *       and {@code locationAddressText}. Matching is done on normalised tokens with a small table of
 *       the abbreviations this domain actually uses, because {@code DOB} shares no substring at all
 *       with {@code birthDate} and every generic string-similarity measure scores it zero.
 *   <li><b>The type.</b> A canonical field wanting a date is not served by a column of free text
 *       without a parse in between, and the transform proposed reflects that.
 * </ol>
 *
 * <p>Every suggestion says which of these it rested on. An author who disagrees needs to see the
 * reasoning, not a number.
 */
public final class DeterministicAdvisor implements MappingAdvisor {

    /**
     * Abbreviations this domain actually uses.
     *
     * <p>Hand-written rather than inferred. A CAD export writes {@code DOB} and {@code RPT_DTTM},
     * and no amount of edit distance connects those to {@code birthDate} and
     * {@code reportedDateTime}. This table is the difference between an advisor that is useful on
     * a real feed and one that only matches columns already named after the canonical model.
     */
    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("dob", "birth date"),
            Map.entry("dttm", "date time"),
            Map.entry("dt", "date"),
            Map.entry("tm", "time"),
            Map.entry("addr", "address"),
            Map.entry("nbr", "number"),
            Map.entry("num", "number"),
            Map.entry("no", "number"),
            Map.entry("id", "identifier"),
            Map.entry("desc", "description"),
            Map.entry("cd", "code"),
            Map.entry("nm", "name"),
            Map.entry("fname", "given name"),
            Map.entry("lname", "sur name"),
            Map.entry("surname", "sur name"),
            Map.entry("lastname", "sur name"),
            Map.entry("firstname", "given name"),
            Map.entry("sex", "sex code"),
            Map.entry("gender", "sex code"),
            Map.entry("dl", "driver license"),
            Map.entry("ssn", "social security"),
            Map.entry("inc", "incident"),
            Map.entry("rpt", "reported"),
            Map.entry("loc", "location"),
            Map.entry("stat", "status"),
            Map.entry("qty", "quantity"));

    /** Shapes that identify a date, and the pattern that parses each. */
    private static final Map<String, String> DATE_SHAPES = Map.of(
            "##/##/####", "MM/dd/yyyy",
            "####-##-##", "yyyy-MM-dd",
            "##-##-####", "MM-dd-yyyy",
            "####/##/##", "yyyy/MM/dd");

    // Whitespace renders as '_' in a ValueShape, not as a space -- see ValueShape.pattern.
    private static final Map<String, String> DATE_TIME_SHAPES = Map.of(
            "####/##/##_##:##", "yyyy/MM/dd HH:mm",
            "####-##-##_##:##", "yyyy-MM-dd HH:mm",
            "##/##/####_##:##", "MM/dd/yyyy HH:mm",
            "####-##-##A##:##", "yyyy-MM-dd'T'HH:mm");

    /** Below this, a name match is noise rather than a signal. */
    private static final double NAME_FLOOR = 0.45;

    @Override
    public String id() {
        return "bundled-deterministic";
    }

    @Override
    public List<Suggestion> suggest(Context context) {
        Set<String> mapped = Set.copyOf(context.alreadyMapped());
        List<Suggestion> suggestions = new ArrayList<>();

        for (CanonicalFieldDescriptor field : context.target().fields()) {
            if (mapped.contains(field.name())) {
                continue;
            }
            best(field, context).ifPresent(suggestions::add);
        }

        // Most confident first. An author works down the list and stops when it stops being
        // obviously right, which only works if the obviously-right ones are at the top.
        suggestions.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));
        return List.copyOf(suggestions);
    }

    private Optional<Suggestion> best(CanonicalFieldDescriptor field, Context context) {
        Suggestion best = null;
        for (String column : context.sourceColumns()) {
            Suggestion candidate = propose(field, column, context);
            if (candidate != null && (best == null || candidate.confidence() > best.confidence())) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    private Suggestion propose(CanonicalFieldDescriptor field, String column, Context context) {
        double nameScore = similarity(column, field.name());
        ValueShape shape = context.shapes().get(column);
        String pattern = shape == null ? null : shape.pattern();

        // The shape decides the transform; the name decides which field it belongs to.
        String dateFormat = pattern == null ? null : DATE_SHAPES.get(pattern);
        String dateTimeFormat = pattern == null ? null : DATE_TIME_SHAPES.get(pattern);

        if (field.type() == FieldType.DATE && dateFormat != null && nameScore >= NAME_FLOOR) {
            return new Suggestion(field.name(), "parseDate", List.of(column),
                    Map.of("pattern", dateFormat),
                    boost(nameScore, 0.25),
                    "'%s' always looks like %s, which is %s, and its name matches '%s'"
                            .formatted(column, pattern, dateFormat, field.name()));
        }
        if (field.type() == FieldType.DATE_TIME && dateTimeFormat != null && nameScore >= NAME_FLOOR) {
            return new Suggestion(field.name(), "parseDateTime", List.of(column),
                    // Zone deliberately absent. A CAD export writes local wall-clock time with no
                    // offset, and a guessed zone shifts every record in the agency by hours; the
                    // author has to state it.
                    Map.of("pattern", dateTimeFormat),
                    boost(nameScore, 0.2),
                    "'%s' always looks like %s. Set the zone before accepting — it cannot be guessed"
                            .formatted(column, pattern));
        }

        if (nameScore < NAME_FLOOR) {
            return null;
        }

        String transform = switch (field.type()) {
            case DATE -> "parseDate";
            case DATE_TIME -> "parseDateTime";
            case INTEGER -> "parseInteger";
            case DECIMAL -> "parseDecimal";
            default -> "copy";
        };

        String rationale = transform.equals("copy")
                ? "'%s' matches '%s' by name".formatted(column, field.name())
                : "'%s' matches '%s' by name, and '%s' is %s so it needs parsing"
                        .formatted(column, field.name(), field.name(),
                                field.type().name().toLowerCase(Locale.ROOT));

        if (!context.transformTypes().contains(transform)) {
            return null;
        }
        // Weaker without a shape to corroborate it: a name match alone is a guess about intent, and
        // the confidence shown should say so.
        return new Suggestion(field.name(), transform, List.of(column),
                transform.startsWith("parseDate") ? Map.of() : Map.of(),
                shape == null ? nameScore * 0.8 : nameScore,
                rationale + (shape == null ? "" : ". Nothing in its shape contradicts that"));
    }

    private static double boost(double score, double by) {
        return Math.min(1.0, score + by);
    }

    /**
     * How alike two field names are, after both are reduced to words.
     *
     * <p>Token overlap rather than edit distance. {@code RPT_DTTM} and {@code reportedDateTime}
     * share no useful substring and would score near zero on any character measure; expanded to
     * "reported date time" against "reported date time" they are identical, which is the truth.
     */
    static double similarity(String column, String field) {
        List<String> left = tokens(column);
        List<String> right = tokens(field);
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        long shared = left.stream().filter(right::contains).count();
        if (shared == 0) {
            return 0;
        }
        // Symmetric: a column matching every word of a field it only half covers is not a full
        // match, and neither is the reverse.
        double coverageOfField = (double) shared / right.size();
        double coverageOfColumn = (double) shared / left.size();
        return (coverageOfField + coverageOfColumn) / 2;
    }

    /** Splits a name into lower-case words, expanding the abbreviations this domain uses. */
    static List<String> tokens(String name) {
        String spaced = name
                // INC_NUM and incidentNumber both have to end up as words.
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("[_\\-.]+", " ")
                .toLowerCase(Locale.ROOT);

        List<String> tokens = new ArrayList<>();
        for (String word : spaced.split("\\s+")) {
            if (word.isBlank()) {
                continue;
            }
            String expanded = SYNONYMS.get(word);
            if (expanded != null) {
                tokens.addAll(List.of(expanded.split(" ")));
            } else {
                tokens.add(word);
            }
        }
        return tokens;
    }
}
