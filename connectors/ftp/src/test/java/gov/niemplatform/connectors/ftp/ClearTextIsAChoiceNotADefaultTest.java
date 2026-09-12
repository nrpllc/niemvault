package gov.niemplatform.connectors.ftp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.connectors.api.ConnectorConfig;
import gov.niemplatform.connectors.api.ConnectorConfigurationException;
import gov.niemplatform.connectors.api.RetentionPosture;
import gov.niemplatform.connectors.api.SourceCheckpointStore;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sending a credential in clear text is something an operator writes down (spec §9, ADR 0033).
 *
 * <p>The setting this connector exists to be careful about. Plain FTP works everywhere, which is
 * exactly the problem: an operator troubleshooting a TLS negotiation failure will try settings
 * until one succeeds, and {@code none} is the one that always succeeds. Requiring it to be declared
 * puts that decision in a reviewable file rather than in someone's recollection of what they tried.
 */
class ClearTextIsAChoiceNotADefaultTest {

    @Test
    @DisplayName("a connection that says nothing about TLS is refused, not quietly secured")
    void securityIsRequired() {
        assertThatThrownBy(() -> configure(without("security")))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("security")
                .hasMessageContaining("clear text");
    }

    @Test
    @DisplayName("explicit, implicit and none are the only answers, and each is accepted")
    void securityValues() {
        for (String value : new String[] {"explicit", "implicit", "none"}) {
            Map<String, String> settings = baseline();
            settings.put("security", value);
            assertThat(configure(settings).retention()).isEqualTo(RetentionPosture.RETAINED);
        }
        Map<String, String> nonsense = baseline();
        nonsense.put("security", "tls");
        assertThatThrownBy(() -> configure(nonsense))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("'explicit', 'implicit' or 'none'");
    }

    /**
     * The historical port split. Implicit FTPS is TLS from the first byte and has always lived on
     * 990; explicit negotiates up from 21. Defaulting both to 21 would make an implicit connection
     * fail with a timeout that says nothing about why.
     */
    @Test
    @DisplayName("implicit TLS defaults to port 990 and explicit to 21")
    void portFollowsSecurity() {
        Map<String, String> implicitSettings = baseline();
        implicitSettings.put("security", "implicit");
        assertThat(configure(implicitSettings).toString()).contains(":990");

        Map<String, String> explicitSettings = baseline();
        explicitSettings.put("security", "explicit");
        assertThat(configure(explicitSettings).toString()).contains(":21");
    }

    @Test
    @DisplayName("an explicit port is honoured whatever the security posture")
    void anExplicitPortWins() {
        Map<String, String> settings = baseline();
        settings.put("security", "implicit");
        settings.put("port", "2121");

        assertThat(configure(settings).toString()).contains(":2121");
    }

    @Test
    @DisplayName("the connection mode is passive by default, because firewalls")
    void passiveByDefault() {
        assertThatThrownBy(() -> {
            Map<String, String> settings = baseline();
            settings.put("connectionMode", "sideways");
            configure(settings);
        })
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("passive");
    }

    @Test
    @DisplayName("a pull that does not say who remembers is refused, as on every other pull")
    void afterDownloadIsRequired() {
        assertThatThrownBy(() -> configure(without("afterDownload")))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("afterDownload")
                .hasMessageContaining("ADR 0031");
    }

    @Test
    @DisplayName("a pull that does not state its retention posture is refused, as on every other pull")
    void retentionIsRequired() {
        assertThatThrownBy(() -> configure(without("retention")))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("ADR 0027");
    }

    @Test
    @DisplayName("a watermark pull given nowhere to keep its position is refused before it runs")
    void watermarkNeedsACheckpointStore() {
        Map<String, String> settings = baseline();
        settings.put("afterDownload", "watermark");
        FtpsConnector connector = new FtpsConnector();
        connector.useCheckpointStore(SourceCheckpointStore.unavailable());

        assertThatThrownBy(() -> connector.configure(
                ConnectorConfig.of("riverton-pd-cad", "cad-ftps-1", FtpsConnector.TYPE, settings)))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("checkpointStore");
    }

    @Test
    @DisplayName("a misspelled setting is an error, because a silently ignored one is how a rule goes missing")
    void unknownSettingsAreRefused() {
        Map<String, String> settings = baseline();
        settings.put("securty", "explicit");

        assertThatThrownBy(() -> configure(settings))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("securty");
    }

    /**
     * An operator reading a log line is entitled to see that the session is in the clear.
     *
     * <p>The one setting whose wrong value is both invisible in operation and serious: a plain-FTP
     * pull works exactly as well as an FTPS one right up until someone is on the wire.
     */
    @Test
    @DisplayName("a connector prints its security posture, and never the credential")
    void printsThePostureAndNoSecret() {
        Map<String, String> settings = baseline();
        settings.put("security", "none");

        assertThat(configure(settings).toString())
                .contains("security=none")
                .contains("ftp.county.example")
                .doesNotContain("correct-horse");
    }

    private static FtpsConnector configure(Map<String, String> settings) {
        FtpsConnector connector = new FtpsConnector();
        connector.useCheckpointStore(SourceCheckpointStore.inMemory());
        connector.configure(
                ConnectorConfig.of("riverton-pd-cad", "cad-ftps-1", FtpsConnector.TYPE, settings));
        return connector;
    }

    private static Map<String, String> baseline() {
        Map<String, String> settings = new HashMap<>();
        settings.put("host", "ftp.county.example");
        settings.put("username", "niem");
        settings.put("password", "correct-horse");
        settings.put("security", "explicit");
        settings.put("directory", "/drop");
        settings.put("afterDownload", "none");
        settings.put("retention", "retained");
        return settings;
    }

    private static Map<String, String> without(String key) {
        Map<String, String> settings = baseline();
        settings.remove(key);
        return settings;
    }
}
