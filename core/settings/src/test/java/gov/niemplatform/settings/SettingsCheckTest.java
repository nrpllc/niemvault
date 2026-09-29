package gov.niemplatform.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettingsCheckTest {

    private static final List<SettingDescriptor> DECLARED = List.of(
            SettingDescriptor.text("topic").required().build(),
            SettingDescriptor.integer("maxRecords").defaultsTo("100000").build(),
            SettingDescriptor.bool("synthetic").build(),
            SettingDescriptor.choice("retention", "retained", "transient").required().build(),
            SettingDescriptor.text("passwordEnv").envVarName().build(),
            SettingDescriptor.text("password").secretValue().build());

    private static List<String> keys(List<SettingsCheck.Problem> problems) {
        return problems.stream().map(SettingsCheck.Problem::key).toList();
    }

    @Test
    @DisplayName("well-formed settings pass")
    void passes() {
        assertThat(SettingsCheck.check(DECLARED, Map.of(
                "topic", "cad", "maxRecords", "500", "synthetic", "TRUE",
                "retention", "retained", "passwordEnv", "NIEM_ODS_PASSWORD"))).isEmpty();
    }

    @Test
    @DisplayName("every problem is reported at once: missing, malformed, unknown")
    void everyProblem() {
        var problems = SettingsCheck.check(DECLARED, Map.of(
                "maxRecords", "ten", "synthetic", "yes", "retention", "forever", "topik", "cad"));

        assertThat(keys(problems)).containsExactlyInAnyOrder(
                "topic", "maxRecords", "synthetic", "retention", "topik");
        assertThat(problems).anySatisfy(p -> assertThat(p.detail()).contains("not a setting"));
    }

    @Test
    @DisplayName("a secret this component reads literally is never accepted into an artifact")
    void literalSecretRefused() {
        var problems = SettingsCheck.check(DECLARED,
                Map.of("topic", "t", "retention", "retained", "password", "hunter2"));

        assertThat(problems).singleElement().satisfies(p -> {
            assertThat(p.key()).isEqualTo("password");
            assertThat(p.detail()).contains("ADR 0015").doesNotContain("hunter2");
        });
    }

    @Test
    @DisplayName("an env-var setting holding something that is not a variable name is refused, not echoed")
    void envVarNameShape() {
        var problems = SettingsCheck.check(DECLARED,
                Map.of("topic", "t", "retention", "retained", "passwordEnv", "s3cret pass!"));

        assertThat(problems).singleElement()
                .satisfies(p -> assertThat(p.detail()).doesNotContain("s3cret pass!"));
    }

    @Test
    @DisplayName("a component that declares nothing is left to its own configure()")
    void undeclared() {
        assertThat(SettingsCheck.check(List.of(), Map.of("anything", "x"))).isEmpty();
    }

    @Test
    @DisplayName("a choice with no options is a programming error")
    void choiceNeedsOptions() {
        assertThatThrownBy(() -> SettingDescriptor.choice("mode").build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
