package gov.niemplatform.connectors.sftp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.connectors.api.ConnectorConfig;
import gov.niemplatform.connectors.api.ConnectorConfigurationException;
import gov.niemplatform.connectors.api.RetentionPosture;
import gov.niemplatform.connectors.api.SourceCheckpointStore;
import gov.niemplatform.connectors.pull.AfterDownload;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A pull is refused rather than guessed at (spec §9, ADR 0010, ADR 0027, ADR 0030, ADR 0031).
 *
 * <p>Three of this connector's settings have no default, and each is a question whose wrong answer
 * is silent: who remembers what was landed, whether the records may be kept at all, and whether the
 * server on the other end is the one intended. A connector that defaulted any of them would run
 * perfectly and be wrong in a way nothing reports.
 */
class ConfiguringAPullStatesWhoRemembersTest {

    @Test
    @DisplayName("a pull that does not say who remembers is refused, and told what the choices cost")
    void afterDownloadIsRequired() {
        assertThatThrownBy(() -> configure(without("afterDownload")))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("afterDownload")
                .hasMessageContaining("archive")
                .hasMessageContaining("watermark")
                .hasMessageContaining("re-reads");
    }

    @Test
    @DisplayName("a pull that does not state its retention posture is refused")
    void retentionIsRequired() {
        assertThatThrownBy(() -> configure(without("retention")))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("retention")
                .hasMessageContaining("ADR 0027");
    }

    /**
     * The posture cannot be read before it is configured, rather than defaulting to retained.
     *
     * <p>Retained is the only default that would let a run proceed, and arriving at it by omission
     * is an unlawful retention nobody chose.
     */
    @Test
    @DisplayName("retention cannot be read before configuration rather than being assumed")
    void retentionRefusesToGuess() {
        assertThatThrownBy(() -> new SftpConnector().retention())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another organisation's server");
    }

    @Test
    @DisplayName("a pull that verifies no host key is refused unless it says so explicitly")
    void hostKeyVerificationIsRequired() {
        assertThatThrownBy(() -> configure(without("hostKeyCheck")))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("hostKeyFingerprint")
                .hasMessageContaining("whatever answers on that address");
    }

    @Test
    @DisplayName("two ways of checking the host key at once is a contradiction, not a preference")
    void hostKeyVerificationIsSingular() {
        Map<String, String> settings = baseline();
        settings.put("hostKeyFingerprint", "SHA256:whatever");

        assertThatThrownBy(() -> configure(settings))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("only one of");
    }

    @Test
    @DisplayName("a pull with no credential is refused; there is no anonymous SFTP")
    void aCredentialIsRequired() {
        assertThatThrownBy(() -> configure(without("password")))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("password");
    }

    /**
     * A password and a key together is ambiguous in a way that hides a rotation failure.
     *
     * <p>Which one authenticated would depend on what the server offered, so an operator rotating
     * the unused one would see nothing change and believe the rotation took.
     */
    @Test
    @DisplayName("a password and a private key together is refused rather than ordered")
    void oneCredentialOnly() {
        Map<String, String> settings = baseline();
        settings.put("privateKeyPath", "/tmp/id_ed25519");

        assertThatThrownBy(() -> configure(settings))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("cannot be set alongside");
    }

    @Test
    @DisplayName("archiving with nowhere to archive to is refused")
    void archiveNeedsADestination() {
        Map<String, String> settings = baseline();
        settings.put("afterDownload", "archive");

        assertThatThrownBy(() -> configure(settings))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("archiveDirectory");
    }

    /**
     * A setting that cannot take effect is worse than a missing one.
     *
     * <p>An operator who wrote an archive directory believes files are being archived. Nothing on a
     * successful run would tell them otherwise.
     */
    @Test
    @DisplayName("an archive directory that nothing will ever use is refused, not ignored")
    void anInertSettingIsRefused() {
        Map<String, String> settings = baseline();
        settings.put("archiveDirectory", "/archive");

        assertThatThrownBy(() -> configure(settings))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("will ever be moved there");
    }

    @Test
    @DisplayName("a misspelled setting is an error, because a silently ignored one is how a rule goes missing")
    void unknownSettingsAreRefused() {
        Map<String, String> settings = baseline();
        settings.put("directroy", "/drop");

        assertThatThrownBy(() -> configure(settings))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("directroy");
    }

    /**
     * A watermark pull with nowhere to write its position must fail at configuration time.
     *
     * <p>The alternative runs perfectly and re-lands the whole directory on every invocation, with
     * every log line reporting success. Bronze simply grows by a full copy a night.
     */
    @Test
    @DisplayName("a watermark pull given nowhere to keep its position is refused before it runs")
    void watermarkNeedsACheckpointStore() {
        Map<String, String> settings = baseline();
        settings.put("afterDownload", "watermark");
        SftpConnector connector = new SftpConnector();
        connector.useCheckpointStore(SourceCheckpointStore.unavailable());

        assertThatThrownBy(() -> connector.configure(
                ConnectorConfig.of("riverton-pd-cad", "cad-sftp-1", SftpConnector.TYPE, settings)))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("checkpointStore");
    }

    /** The postures that let the remote server remember need no store, and must not demand one. */
    @Test
    @DisplayName("an archiving pull needs no checkpoint store, because the server remembers")
    void archiveNeedsNoCheckpointStore() {
        Map<String, String> settings = baseline();
        settings.put("afterDownload", "archive");
        settings.put("archiveDirectory", "/archive");
        SftpConnector connector = new SftpConnector();
        connector.useCheckpointStore(SourceCheckpointStore.unavailable());

        connector.configure(
                ConnectorConfig.of("riverton-pd-cad", "cad-sftp-1", SftpConnector.TYPE, settings));

        assertThat(connector.retention()).isEqualTo(RetentionPosture.RETAINED);
    }

    @Test
    @DisplayName("every problem is reported at once, so an operator fixes them in one pass")
    void allProblemsAtOnce() {
        Map<String, String> settings = baseline();
        settings.remove("afterDownload");
        settings.remove("retention");
        settings.put("port", "70000");

        assertThatThrownBy(() -> configure(settings))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("afterDownload")
                .hasMessageContaining("retention")
                .hasMessageContaining("port");
    }

    /**
     * A source must be describable by someone who is not standing in its deployment.
     *
     * <p>The boundary {@code FileDropConnector} draws around its drop directory, and the same
     * reason: configuring asks whether the configuration is well-formed, and whether the source can
     * be reached is {@code health()}'s question. A records manager reviewing this definition on
     * their own laptop has no {@code /etc/niem/secrets}, and a catalogue that refused to describe
     * the source for that reason would be unusable exactly where it is most useful.
     */
    @Test
    @DisplayName("a key file that is not on this machine does not make the definition malformed")
    void aMissingKeyIsNotAConfigurationProblem() {
        Map<String, String> settings = baseline();
        settings.remove("password");
        settings.put("privateKeyPath", "/etc/niem/secrets/county-sftp/id_ed25519");

        SftpConnector connector = configure(settings);

        assertThat(connector.retention()).isEqualTo(RetentionPosture.RETAINED);
        // And it is reported where it belongs, in terms that point at the right side of the wire.
        assertThat(connector.health().toString())
                .contains("UNAVAILABLE")
                .contains("not a credential the far side rejected");
    }

    @Test
    @DisplayName("a connector prints its endpoint and its postures, and never a credential")
    void printsNoSecret() {
        SftpConnector connector = configure(baseline());

        assertThat(connector.toString())
                .contains("sftp.riverton.example")
                .contains("afterDownload=none")
                .doesNotContain("correct-horse");
    }

    private static SftpConnector configure(Map<String, String> settings) {
        SftpConnector connector = new SftpConnector();
        connector.useCheckpointStore(SourceCheckpointStore.inMemory());
        connector.configure(
                ConnectorConfig.of("riverton-pd-cad", "cad-sftp-1", SftpConnector.TYPE, settings));
        return connector;
    }

    /** A configuration that is complete and valid, for a test to spoil one setting of. */
    private static Map<String, String> baseline() {
        Map<String, String> settings = new HashMap<>();
        settings.put("host", "sftp.riverton.example");
        settings.put("username", "niem");
        settings.put("password", "correct-horse");
        settings.put("hostKeyCheck", "accept-any");
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
