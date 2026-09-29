package gov.niemplatform.projections.search;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.settings.SettingDescriptor;
import org.junit.jupiter.api.Test;

/** Search describes the settings its factory reads (ADR 0037). */
class SearchSettingsTest {

    @Test
    void describesWhatItReads() {
        var factory = new ElasticsearchProjectionFactory();
        assertThat(factory.settings().stream().map(SettingDescriptor::key).toList())
                .containsExactlyInAnyOrder("url", "user", "passwordEnv", "indexPrefix", "refresh",
                        "timeoutSeconds");
        assertThat(factory.summary()).isNotBlank();
    }
}
