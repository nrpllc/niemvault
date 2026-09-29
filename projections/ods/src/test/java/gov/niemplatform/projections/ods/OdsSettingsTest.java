package gov.niemplatform.projections.ods;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.settings.SettingDescriptor;
import org.junit.jupiter.api.Test;

/** The ODS describes exactly the settings it accepts (ADR 0037). */
class OdsSettingsTest {

    @Test
    void describesWhatItReads() {
        var factory = new PostgresOdsProjectionFactory();
        assertThat(factory.settings().stream().map(SettingDescriptor::key).toList())
                .containsExactlyInAnyOrderElementsOf(PostgresOdsProjectionFactory.SETTINGS);
        assertThat(factory.settings()).filteredOn(s -> s.key().equals("passwordEnv"))
                .singleElement().extracting(SettingDescriptor::sensitivity)
                .isEqualTo(SettingDescriptor.Sensitivity.ENV_VAR_NAME);
        assertThat(factory.summary()).isNotBlank();
    }
}
