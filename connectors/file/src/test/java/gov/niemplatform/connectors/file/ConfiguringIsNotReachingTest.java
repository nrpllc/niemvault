package gov.niemplatform.connectors.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.connectors.api.ConnectorConfig;
import gov.niemplatform.connectors.api.ConnectorConfigurationException;
import gov.niemplatform.connectors.api.HealthStatus;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Configuring asks whether the settings are well-formed. Reaching the source is a different question.
 *
 * <p>The two were conflated: {@code configure} refused a drop directory that did not exist, which
 * {@code health} already reports as {@code UNAVAILABLE}. The duplication was harmless until anything
 * wanted to <em>describe</em> a source without standing in its environment — a records manager
 * reviewing a definition on their own laptop has no {@code /var/spool/cad}, and a catalogue that
 * refused to describe the source for that reason would be useless exactly where it is most needed.
 */
class ConfiguringIsNotReachingTest {

    @TempDir
    Path work;

    private static ConnectorConfig configFor(Path directory) {
        return ConnectorConfig.of("riverton-pd-cad", "cad-file-drop-1", FileDropConnector.TYPE,
                Map.of("directory", directory.toString(), "filePattern", "*.csv"));
    }

    @Test
    @DisplayName("a source can be configured, and so described, without its environment present")
    void configuresAgainstADirectoryThatIsNotThere() {
        var connector = new FileDropConnector();

        assertThatCode(() -> connector.configure(configFor(work.resolve("not-here"))))
                .doesNotThrowAnyException();
        assertThat(connector.retention().landsInBronze()).isTrue();
    }

    @Test
    @DisplayName("but an absent directory is still reported, as the health question it is")
    void reportsTheAbsenceAsHealth() {
        var connector = new FileDropConnector();
        connector.configure(configFor(work.resolve("not-here")));

        HealthStatus health = connector.health();

        // Nothing is lost by moving the check: `run` asks this before it lands anything, so an
        // operator still finds out before a batch is half-committed rather than during.
        assertThat(health.isHealthy()).isFalse();
        assertThat(health.state()).isEqualTo(HealthStatus.State.UNAVAILABLE);
        assertThat(health.detail()).contains("not-here");
    }

    @Test
    @DisplayName("a malformed setting is still a configuration problem, not a health one")
    void stillRefusesSettingsItCannotUse() {
        // The split is between well-formed and reachable, not between strict and lax. A record mode
        // this connector does not have is unusable wherever it is read from.
        assertThatThrownBy(() -> new FileDropConnector().configure(ConnectorConfig.of(
                "riverton-pd-cad", "cad-file-drop-1", FileDropConnector.TYPE,
                Map.of("directory", work.toString(), "recordMode", "teleport"))))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("recordMode");
    }
}
