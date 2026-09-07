package gov.niemplatform.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Text edits to a mapping.
 *
 * <p>Almost every test here asserts on what did <em>not</em> change. That is the point of the
 * class: a form that edits a mapping must leave the file otherwise identical, because a shipped
 * mapping's comments carry the reasoning behind its steps and a round trip would erase them.
 */
class MappingTextTest {

    private String yaml;

    @BeforeEach
    void readTheShippedMapping() throws Exception {
        Path file = Path.of("").toAbsolutePath().getParent()
                .resolve("modules/law-enforcement/src/main/resources/mappings/cad-to-canonical-1.0.0.yaml");
        yaml = Files.readString(file, StandardCharsets.UTF_8);
    }

    private MappingText text() {
        return new MappingText(yaml);
    }

    /**
     * Lines that changed, by position — the assertion most of these tests want.
     *
     * <p>Positional, not by membership: "type: copy" occurs in several steps, so a membership test
     * would call a rewritten line unchanged because an identical one survives elsewhere.
     */
    private static List<String> changed(String before, String after) {
        List<String> from = before.lines().toList();
        List<String> to = after.lines().toList();
        assertThat(to).as("a scalar edit must not change the line count").hasSameSizeAs(from);

        List<String> differences = new java.util.ArrayList<>();
        for (int line = 0; line < from.size(); line++) {
            if (!from.get(line).equals(to.get(line))) {
                differences.add("- " + from.get(line));
                differences.add("+ " + to.get(line));
            }
        }
        return differences;
    }

    @Nested
    @DisplayName("Editing a scalar")
    class Scalars {

        @Test
        @DisplayName("rewrites exactly the one line it was asked to")
        void rewritesOneLine() {
            String edited = text().setStepField("map-incident", 0, "type", "trim").text();

            assertThat(changed(yaml, edited))
                    .containsExactly("-         type: copy", "+         type: trim");
        }

        @Test
        @DisplayName("keeps every comment in the file")
        void keepsComments() {
            String edited = text().setStepField("map-person", 1, "type", "lower").text();

            long before = yaml.lines().filter(line -> line.stripLeading().startsWith("#")).count();
            long after = edited.lines().filter(line -> line.stripLeading().startsWith("#")).count();
            assertThat(after).isEqualTo(before);
            assertThat(edited).contains("# \"DOE, JANE M\" -> surName DOE, givenName JANE, middleName M.");
        }

        @Test
        @DisplayName("addresses steps by position, because a hop may target the same field twice")
        void addressesByPosition() {
            // map-person writes surName twice: splitIndex, then upper. Addressing by target would
            // be ambiguous, and picking the first match silently would edit the wrong step.
            String edited = text().setStepField("map-person", 1, "type", "lower").text();

            assertThat(edited).contains("      - target: surName\n        type: splitIndex");
            assertThat(edited).contains("      - target: surName\n        type: lower");
        }

        @Test
        @DisplayName("rewrites the inputs that draw an edge on the graph")
        void rewritesFrom() {
            String edited = text().setStepFrom("map-incident", 0, List.of("INC_NUM", "BEAT")).text();

            assertThat(changed(yaml, edited))
                    .containsExactly("-         from: [INC_NUM]", "+         from: [INC_NUM, BEAT]");
        }

        @Test
        @DisplayName("quotes a value that would not survive unquoted")
        void quotesWhatItMust() {
            String edited = text().setStepField("map-incident", 0, "target", "odd: value").text();
            assertThat(edited).contains("- target: \"odd: value\"");
        }

        @Test
        @DisplayName("refuses to invent a key the step does not declare")
        void refusesToInventAKey() {
            // Adding a line means choosing where to put it, and the file already has an opinion
            // about that. The text editor is right there.
            assertThatThrownBy(() -> text().setStepField("map-incident", 0, "options", "x"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not declare 'options'");
        }

        @Test
        @DisplayName("reports an out-of-range step rather than editing a neighbour")
        void refusesAnUnknownStep() {
            assertThatThrownBy(() -> text().setStepField("map-incident", 99, "type", "trim"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no step 99");
        }
    }

    @Nested
    @DisplayName("Editing an option")
    class Options {

        @Test
        @DisplayName("rewrites a value inside a flow mapping without reflowing the line")
        void editsFlowStyle() {
            // options: { delimiter: ",", index: "0" } -- the rest of the line survives verbatim.
            String edited = text().setStepOption("map-person", 0, "index", "3").text();

            assertThat(changed(yaml, edited)).containsExactly(
                    "-         options: { delimiter: \",\", index: \"0\" }",
                    "+         options: { delimiter: \",\", index: 3 }");
        }

        @Test
        @DisplayName("rewrites a value in a block mapping")
        void editsBlockStyle() {
            String edited = text().setStepOption("map-incident", 1, "zone", "America/Chicago").text();

            assertThat(changed(yaml, edited)).containsExactly(
                    "-           zone: \"America/Denver\"",
                    "+           zone: America/Chicago");
        }

        @Test
        @DisplayName("refuses an option that is absent")
        void refusesAnAbsentOption() {
            assertThatThrownBy(() -> text().setStepOption("map-incident", 0, "pattern", "x"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("edit it as text");
        }

        @Test
        @DisplayName("refuses an option that opens a nested structure")
        void refusesANestedOption() {
            // Written out here rather than taken from the module: every option the shipped mapping
            // declares happens to be a single line, so the rule needs an input that exercises it.
            // Rewriting `map:` as a scalar would orphan every entry beneath it.
            String nested = """
                    hops:
                      - id: classify
                        steps:
                          - target: callTypeCode
                            type: codeMap
                            options:
                              map:
                                BURG: BURG
                                THEFT: THEFT
                    """;

            assertThatThrownBy(() -> new MappingText(nested).setStepOption("classify", 0, "map", "x"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("edit it as text");
        }
    }

    @Nested
    @DisplayName("Adding and removing steps")
    class Structure {

        @Test
        @DisplayName("appends a step at the hop's own indentation")
        void appendsAStep() {
            String edited = text().addStep("map-incident", "beat", "upper", List.of("BEAT")).text();

            assertThat(edited).contains(
                    "      - target: beat\n        type: upper\n        from: [BEAT]");
            assertThat(new MappingText(edited).stepCount("map-incident"))
                    .isEqualTo(text().stepCount("map-incident") + 1);
        }

        @Test
        @DisplayName("takes a step's explanatory comment with it when it goes")
        void removesTheCommentToo() {
            // Deleting the step but leaving "# \"DOE, JANE M\" -> surName DOE..." behind would
            // strand an explanation above whichever step happened to follow.
            String edited = text().removeStep("map-person", 0).text();

            assertThat(edited).doesNotContain("# \"DOE, JANE M\" ->");
            assertThat(edited).doesNotContain("      - target: surName\n        type: splitIndex");
            assertThat(new MappingText(edited).stepCount("map-person"))
                    .isEqualTo(text().stepCount("map-person") - 1);
        }

        @Test
        @DisplayName("leaves the rest of the hop alone when a step is removed")
        void removalIsLocal() {
            String edited = text().removeStep("map-incident", 4).text();

            assertThat(edited).contains("- id: map-person").contains("- id: map-person-incident");
            assertThat(new MappingText(edited).stepCount("map-person"))
                    .isEqualTo(text().stepCount("map-person"));
        }

        @Test
        @DisplayName("refuses to empty a hop")
        void refusesToEmptyAHop() {
            assertThatThrownBy(() -> text().removeStep("map-person-incident", 0))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("emits nothing");
        }

        @Test
        @DisplayName("reorders steps, because order is meaning in this DSL")
        void reordersSteps() {
            // map-person step 1 (`upper` on surName) reads what step 0 produced. Moving it above
            // its own input is exactly the mistake this makes visible.
            String edited = text().moveStep("map-person", 1, -1).text();

            assertThat(edited).contains(
                    "      - target: surName\n        type: upper\n        from: [surName]");
            assertThat(edited.indexOf("type: upper")).isLessThan(edited.indexOf("type: splitIndex"));
            assertThat(new MappingText(edited).stepCount("map-person"))
                    .isEqualTo(text().stepCount("map-person"));
        }

        @Test
        @DisplayName("refuses to move a step off the end")
        void refusesToMoveOffTheEnd() {
            assertThatThrownBy(() -> text().moveStep("map-incident", 0, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("position -1");
        }
    }

    @Nested
    @DisplayName("Every edit stays loadable")
    class StaysLoadable {

        private final MappingWorkspace workspace = new MappingWorkspace(
                Path.of("").toAbsolutePath().getParent()
                        .resolve("modules/law-enforcement/src/main/resources"),
                gov.niemplatform.content.SemanticVersion.parse("0.1.0"));

        @Test
        @DisplayName("a retyped step still loads")
        void retypedStepLoads() {
            var report = workspace.validate(
                    text().setStepField("map-incident", 0, "type", "trim").text());
            assertThat(report.problems()).isEmpty();
        }

        @Test
        @DisplayName("an appended step still loads")
        void appendedStepLoads() {
            var report = workspace.validate(
                    text().addStep("map-incident", "beat", "upper", List.of("BEAT")).text());
            assertThat(report.problems()).isEmpty();
        }

        @Test
        @DisplayName("a reordered hop still loads")
        void reorderedHopLoads() {
            var report = workspace.validate(text().moveStep("map-person", 1, -1).text());
            assertThat(report.problems()).isEmpty();
        }

        @Test
        @DisplayName("an edit that breaks the mapping is caught by the loader, not by this class")
        void breakageIsTheLoadersJob() {
            // MappingText does not judge. It produces text; the runtime's loaders decide.
            var report = workspace.validate(
                    text().setStepField("map-incident", 0, "type", "sharpen").text());
            assertThat(report.valid()).isFalse();
            assertThat(String.join("\n", report.problems())).contains("sharpen");
        }
    }
}
