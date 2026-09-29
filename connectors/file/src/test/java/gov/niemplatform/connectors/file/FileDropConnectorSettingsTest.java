package gov.niemplatform.connectors.file;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.settings.SettingDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The settings this connector describes are exactly the settings it accepts (ADR 0037). A form
 * built from a description that had drifted would offer a key configure() refuses, or hide one it
 * needs.
 */
class FileDropConnectorSettingsTest {

    @Test
    @DisplayName("describes exactly the keys configure() accepts")
    void describesWhatItReads() {
        var described = new FileDropConnector().settings().stream().map(SettingDescriptor::key).toList();

        assertThat(described).doesNotHaveDuplicates();
        assertThat(described).containsExactlyInAnyOrderElementsOf(FileDropConnector.RECOGNISED_SETTINGS);
    }

    @Test
    @DisplayName("says what it is, for a palette")
    void summarises() {
        assertThat(new FileDropConnector().summary()).isNotBlank();
    }
}
