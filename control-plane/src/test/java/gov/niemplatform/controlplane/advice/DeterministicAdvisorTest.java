package gov.niemplatform.controlplane.advice;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.canonical.core.CoreCanonicalTypes;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.observability.ValueShape;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The advisor that runs where no model ever will.
 *
 * <p>Driven against the real CAD export's column names and the shapes its real fixture produces,
 * because the whole question is whether it is useful on a feed nobody wrote for it. An advisor that
 * only matches columns already named after the canonical model is an advisor that helps with the
 * mappings nobody needed help with.
 */
class DeterministicAdvisorTest {

    private static final DeterministicAdvisor ADVISOR = new DeterministicAdvisor();

    /** The columns the law enforcement CAD export actually sends. */
    private static final List<String> CAD_COLUMNS = List.of(
            "INC_NUM", "CALL_TYPE", "RPT_DTTM", "ADDR", "BEAT",
            "ROLE", "NAME_FULL", "DOB", "SEX", "DL_NUM");

    /** Shapes computed from the shipped fixture's first row, exactly as ValueShape renders them. */
    private static final Map<String, ValueShape> CAD_SHAPES = Map.of(
            "INC_NUM", ValueShape.of("2026-000114"),
            "RPT_DTTM", ValueShape.of("2026/03/04 11:20"),
            "ADDR", ValueShape.of("418 W 9TH ST"),
            "DOB", ValueShape.of("03/14/1988"),
            "SEX", ValueShape.of("F"),
            "DL_NUM", ValueShape.of("K447-1902"));

    private static CanonicalTypeDescriptor type(String name) {
        return CoreCanonicalTypes.ALL.stream()
                .filter(descriptor -> descriptor.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> transforms() {
        return List.copyOf(gov.niemplatform.runtime.transforms.TransformFactory.TYPES);
    }

    private static MappingAdvisor.Context context(String canonicalType, List<String> alreadyMapped) {
        return new MappingAdvisor.Context(
                CAD_COLUMNS, CAD_SHAPES, type(canonicalType), alreadyMapped, transforms());
    }

    private static Optional<MappingAdvisor.Suggestion> forField(
            List<MappingAdvisor.Suggestion> suggestions, String field) {
        return suggestions.stream().filter(s -> s.target().equals(field)).findFirst();
    }

    @Nested
    @DisplayName("Reading names a source actually uses")
    class Names {

        @Test
        @DisplayName("connects DOB to birthDate, which shares no substring with it")
        void expandsAbbreviations() {
            // The case that decides whether this is worth shipping. Every character-similarity
            // measure scores DOB against birthDate at zero.
            var suggestion = forField(ADVISOR.suggest(context("Person", List.of())), "birthDate");

            assertThat(suggestion).isPresent();
            assertThat(suggestion.get().from()).containsExactly("DOB");
        }

        @Test
        @DisplayName("connects RPT_DTTM to reportedDateTime")
        void expandsCompoundAbbreviations() {
            var suggestion =
                    forField(ADVISOR.suggest(context("Incident", List.of())), "reportedDateTime");

            assertThat(suggestion).isPresent();
            assertThat(suggestion.get().from()).containsExactly("RPT_DTTM");
        }

        @Test
        @DisplayName("splits both snake case and camel case into the same words")
        void tokenisesBothConventions() {
            assertThat(DeterministicAdvisor.tokens("INC_NUM")).containsExactly("incident", "number");
            assertThat(DeterministicAdvisor.tokens("incidentNumber"))
                    .containsExactly("incident", "number");
            assertThat(DeterministicAdvisor.similarity("INC_NUM", "incidentNumber")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("proposes nothing for a column that means nothing like the field")
        void staysSilentOnNoise() {
            // BEAT and birthDate share no meaning. Proposing it would train an author to stop
            // reading the suggestions, at which point the useful ones stop working too.
            assertThat(DeterministicAdvisor.similarity("BEAT", "birthDate")).isLessThan(0.45);
        }
    }

    @Nested
    @DisplayName("Reading the shape of the data")
    class Shapes {

        @Test
        @DisplayName("picks the date pattern from the shape, which a name could never give it")
        void readsADatePattern() {
            var suggestion = forField(ADVISOR.suggest(context("Person", List.of())), "birthDate")
                    .orElseThrow();

            assertThat(suggestion.transformType()).isEqualTo("parseDate");
            assertThat(suggestion.options()).containsEntry("pattern", "MM/dd/yyyy");
            assertThat(suggestion.rationale()).contains("##/##/####");
        }

        @Test
        @DisplayName("picks a datetime pattern, and refuses to guess the zone")
        void readsADateTimePatternButNotTheZone() {
            // A CAD export writes local wall-clock time with no offset. Guessing the zone shifts
            // every incident in the agency by hours, silently.
            var suggestion =
                    forField(ADVISOR.suggest(context("Incident", List.of())), "reportedDateTime")
                            .orElseThrow();

            assertThat(suggestion.transformType()).isEqualTo("parseDateTime");
            assertThat(suggestion.options()).containsEntry("pattern", "yyyy/MM/dd HH:mm");
            assertThat(suggestion.options()).doesNotContainKey("zone");
            assertThat(suggestion.rationale()).contains("zone");
        }

        @Test
        @DisplayName("is less confident when no shape corroborates the name")
        void confidenceFallsWithoutAShape() {
            // A name match alone is a guess about intent, and the number shown should say so.
            var withShapes = forField(ADVISOR.suggest(context("Person", List.of())), "birthDate")
                    .orElseThrow();
            var withoutShapes = forField(ADVISOR.suggest(new MappingAdvisor.Context(
                    CAD_COLUMNS, Map.of(), type("Person"), List.of(), transforms())), "birthDate")
                    .orElseThrow();

            assertThat(withoutShapes.confidence()).isLessThan(withShapes.confidence());
        }

        @Test
        @DisplayName("works with no shapes at all, because a mapping is often written before any file lands")
        void worksWithoutAnyShapes() {
            var suggestions = ADVISOR.suggest(new MappingAdvisor.Context(
                    CAD_COLUMNS, Map.of(), type("Person"), List.of(), transforms()));

            assertThat(suggestions).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Behaving like an assistant rather than an author")
    class Manners {

        @Test
        @DisplayName("says nothing about fields a step already writes")
        void doesNotRepeatWorkAlreadyDone() {
            var suggestions = ADVISOR.suggest(context("Person", List.of("birthDate", "surName")));

            assertThat(suggestions).extracting(MappingAdvisor.Suggestion::target)
                    .doesNotContain("birthDate", "surName");
        }

        @Test
        @DisplayName("proposes only transforms the runtime actually has")
        void staysInsideTheVocabulary() {
            var suggestions = ADVISOR.suggest(context("Person", List.of()));

            assertThat(suggestions).extracting(MappingAdvisor.Suggestion::transformType)
                    .isSubsetOf(transforms());
        }

        @Test
        @DisplayName("explains every suggestion in words an author can disagree with")
        void explainsItself() {
            // A suggestion that cannot say why has no business entering an artifact that will be
            // audited. This is a hard requirement of ADR 0023, not a nicety.
            assertThat(ADVISOR.suggest(context("Person", List.of())))
                    .isNotEmpty()
                    .allSatisfy(suggestion ->
                            assertThat(suggestion.rationale()).isNotBlank().hasSizeGreaterThan(20));
        }

        @Test
        @DisplayName("puts the most confident first, because that is the order they get read in")
        void ordersByConfidence() {
            var suggestions = ADVISOR.suggest(context("Person", List.of()));

            assertThat(suggestions).isSortedAccordingTo(
                    (a, b) -> Double.compare(b.confidence(), a.confidence()));
        }

        @Test
        @DisplayName("identifies itself, so an author knows what proposed a step")
        void namesItself() {
            assertThat(ADVISOR.id()).isEqualTo("bundled-deterministic");
        }
    }

    @Nested
    @DisplayName("The values it is never given")
    class NoValues {

        @Test
        @DisplayName("the context type has nowhere to put a record value")
        void contextCannotCarryValues() {
            // The enforcement point of ADR 0023. If a value cannot be put in the context, no
            // implementation can leak one -- including one talking to a remote endpoint.
            var components = MappingAdvisor.Context.class.getRecordComponents();

            assertThat(components).extracting(java.lang.reflect.RecordComponent::getName)
                    .containsExactlyInAnyOrder(
                            "sourceColumns", "shapes", "target", "alreadyMapped", "transformTypes");
            assertThat(MappingAdvisor.Context.class.getRecordComponents())
                    .filteredOn(component -> component.getName().equals("shapes"))
                    .singleElement()
                    .satisfies(component -> assertThat(component.getGenericType().getTypeName())
                            .as("shapes must be redacted shapes, never values")
                            .contains(ValueShape.class.getName()));
        }

        @Test
        @DisplayName("a shape discloses the format and not the content")
        void shapesAreRedacted() {
            assertThat(ValueShape.of("03/14/1988").pattern()).isEqualTo("##/##/####");
            assertThat(ValueShape.of("03/14/1988").pattern()).doesNotContain("1988");
        }
    }
}
