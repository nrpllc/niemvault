package gov.niemplatform.projections.graph;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.settings.SettingDescriptor;
import org.junit.jupiter.api.Test;

/** The graph describes the settings its factory reads (ADR 0037). */
class GraphSettingsTest {

    @Test
    void describesWhatItReads() {
        var factory = new Neo4jProjectionFactory();
        assertThat(factory.settings().stream().map(SettingDescriptor::key).toList())
                .containsExactlyInAnyOrder("uri", "user", "passwordEnv", "database");
        assertThat(factory.summary()).isNotBlank();
    }
}
