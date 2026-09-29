package gov.niemplatform.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.runtime.engine.MappingDefinition;
import gov.niemplatform.runtime.engine.MappingLoader;
import gov.niemplatform.runtime.transforms.TransformSpec;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Adding an option to a step, in whatever style the step already writes its options -- the edit a
 * step added in the editor needs before it can compile.
 */
class MappingTextOptionsTest {

    private String yaml;

    @BeforeEach
    void readTheShippedMapping() throws Exception {
        yaml = Files.readString(Path.of("").toAbsolutePath().getParent()
                .resolve("modules/law-enforcement/src/main/resources/mappings/cad-to-canonical-1.0.0.yaml"),
                StandardCharsets.UTF_8);
    }

    private static TransformSpec step(String yaml, String hop, int index) {
        MappingDefinition definition = new MappingLoader().load(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test");
        return definition.hops().stream().filter(h -> h.hopId().equals(hop)).findFirst().orElseThrow()
                .steps().get(index);
    }

    @Test
    @DisplayName("a step with no options gets an options line after its last line")
    void noOptionsYet() {
        String edited = new MappingText(yaml).setStepField("map-incident", 0, "type", "nullIf")
                .addStepOption("map-incident", 0, "values", "UNK, N/A").text();

        assertThat(step(edited, "map-incident", 0).options()).containsEntry("values", "UNK, N/A");
        assertThat(step(edited, "map-incident", 1).type()).isEqualTo("parseDateTime");
    }

    @Test
    @DisplayName("an option joins a flow-style options mapping")
    void flowStyle() {
        TransformSpec before = step(yaml, "map-person", 0);
        assertThat(before.options()).containsKey("delimiter");

        String edited = new MappingText(yaml).addStepOption("map-person", 0, "trim", "true").text();

        assertThat(step(edited, "map-person", 0).options())
                .containsEntry("trim", "true").containsAllEntriesOf(before.options());
    }

    @Test
    @DisplayName("an option joins a block-style options mapping, at its indent")
    void blockStyle() {
        TransformSpec before = step(yaml, "map-incident", 3);
        assertThat(before.type()).isEqualTo("codeMap");
        assertThat(before.options()).containsKeys("map", "default");

        String edited = new MappingText(yaml).addStepOption("map-incident", 3, "ignoreCase", "true").text();

        assertThat(step(edited, "map-incident", 3).options())
                .containsEntry("ignoreCase", "true").containsAllEntriesOf(before.options());
        assertThat(step(edited, "map-incident", 4).target()).isEqualTo("beat");
    }

    @Test
    @DisplayName("an option the step already has is changed, not duplicated")
    void existingOption() {
        String edited = new MappingText(yaml).addStepOption("map-incident", 1, "zone", "UTC").text();

        assertThat(step(edited, "map-incident", 1).options()).containsEntry("zone", "UTC");
        assertThat(edited.lines().count()).isEqualTo(yaml.lines().count());
    }

    @Test
    @DisplayName("a step added under a bare steps: key is valid YAML, where \"steps: []\" would not be")
    void addsUnderBareStepsKey() {
        String withEmpty = """
                mapping: t
                version: "1.0.0"
                source: s
                decode:
                  format: delimited
                  emits: "source:s/records"
                  columns:
                    - name: A
                hops:
                  - id: h
                    contract: c
                    version: "1.0.0"
                    steps:
                    identity:
                      mode: derive
                      entityType: Incident
                      deriveFrom: [incidentNumber]
                      prefix: "INC/"
                """;
        String edited = new MappingText(withEmpty).addStep("h", "incidentNumber", "nullIf", java.util.List.of("A"))
                .addStepOption("h", 0, "values", "UNK").text();

        assertThat(step(edited, "h", 0).from()).containsExactly("A");
        assertThat(step(edited, "h", 0).options()).containsEntry("values", "UNK");
    }
}
