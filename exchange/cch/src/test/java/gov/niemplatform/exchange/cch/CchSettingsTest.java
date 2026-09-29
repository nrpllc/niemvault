package gov.niemplatform.exchange.cch;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.settings.SettingDescriptor;
import org.junit.jupiter.api.Test;

/** The CCH exchange describes exactly the settings it reads (ADR 0037). */
class CchSettingsTest {

    @Test
    void describesWhatItReads() {
        var writer = new CchExchangeWriter();
        assertThat(writer.settings().stream().map(SettingDescriptor::key).toList())
                .containsExactlyInAnyOrder(CchExchangeWriter.SETTING_ENDPOINT,
                        CchExchangeWriter.SETTING_TOKEN_ENV, CchExchangeWriter.SETTING_TIMEOUT_SECONDS,
                        CchExchangeWriter.SETTING_BATCH_SIZE, CchExchangeWriter.SETTING_SYNTHETIC);
        assertThat(writer.summary()).isNotBlank();
    }
}
