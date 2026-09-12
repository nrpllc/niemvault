package gov.niemplatform.connectors.ftp;

import gov.niemplatform.connectors.api.ConnectorConfig;
import gov.niemplatform.connectors.api.ConnectorConfigurationException;
import gov.niemplatform.connectors.api.ConnectorType;
import gov.niemplatform.connectors.api.HealthStatus;
import gov.niemplatform.connectors.api.InteractionMode;
import gov.niemplatform.connectors.api.RetentionPosture;
import gov.niemplatform.connectors.api.SourceCheckpoint;
import gov.niemplatform.connectors.api.SourceCheckpointStore;
import gov.niemplatform.connectors.api.SourceConnector;
import gov.niemplatform.connectors.api.SourceHandle;
import gov.niemplatform.connectors.pull.AfterDownload;
import gov.niemplatform.connectors.pull.PullSourceHandle;
import gov.niemplatform.connectors.pull.RecordMode;
import gov.niemplatform.connectors.pull.RemoteFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;

/**
 * Pulls files from a remote directory over FTPS, and over plain FTP where an operator insists
 * (spec §4.3, ADR 0033).
 *
 * <p>A separate connector from SFTP rather than a mode of it. The two share everything about what a
 * pull <em>means</em> -- which is why that lives in {@code connectors:pull} and is written once --
 * and nothing about how a session is established. SFTP authenticates a host key and may use a
 * private key; FTPS negotiates TLS, validates a certificate chain, and has to be told whether to
 * open data connections actively or passively. A single connector covering both would have a
 * settings surface where half the keys are inert depending on the other half, and an inert setting
 * is how an operator comes to believe something is configured when it is not.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Because state and county systems still run it. FTPS is FTP with TLS, it is what a 2005-era
 * export job speaks, and an agency does not get to choose what the far side offers. Refusing to
 * implement it would not make those feeds go away; it would make them arrive by someone emailing a
 * spreadsheet.
 *
 * <h2>Plain FTP</h2>
 *
 * <p>Supported, and deliberately awkward to switch on. {@code security: none} sends the credential
 * and then every record in clear text across whatever network sits between. That is occasionally
 * defensible -- a private circuit, a closed lab -- and never defensible by accident, so it has no
 * default and the setting is named for what it does rather than for the protocol.
 *
 * <h2>Settings</h2>
 *
 * <table>
 *   <caption>Transport settings</caption>
 *   <tr><th>Key</th><th>Meaning</th></tr>
 *   <tr><td>{@code host}</td><td>Required.</td></tr>
 *   <tr><td>{@code port}</td><td>Default {@code 21}, or {@code 990} when {@code security} is {@code implicit}.</td></tr>
 *   <tr><td>{@code username}</td><td>Required.</td></tr>
 *   <tr><td>{@code password}</td><td>Required. Never logged.</td></tr>
 *   <tr><td>{@code security}</td><td>Required, no default. {@code explicit}, {@code implicit} or {@code none}.</td></tr>
 *   <tr><td>{@code connectionMode}</td><td>{@code passive} (default) or {@code active}.</td></tr>
 *   <tr><td>{@code directory}</td><td>Required. Remote directory to pull from.</td></tr>
 *   <tr><td>{@code filePattern}</td><td>Glob. Default {@code *}.</td></tr>
 *   <tr><td>{@code afterDownload}</td><td>Required, no default. {@code archive}, {@code delete}, {@code watermark} or {@code none}.</td></tr>
 *   <tr><td>{@code archiveDirectory}</td><td>Required when {@code afterDownload} is {@code archive}.</td></tr>
 *   <tr><td>{@code retention}</td><td>Required, no default. {@code retained} or {@code transient}.</td></tr>
 *   <tr><td>{@code recordMode}</td><td>{@code line} (default) or {@code file}.</td></tr>
 *   <tr><td>{@code skipHeaderLines}</td><td>Default {@code 0}.</td></tr>
 *   <tr><td>{@code charset}</td><td>Default {@code UTF-8}.</td></tr>
 *   <tr><td>{@code maxFiles}</td><td>Ceiling on one pull. Default {@code 1000}.</td></tr>
 *   <tr><td>{@code timeoutSeconds}</td><td>Connect and data timeout. Default {@code 30}.</td></tr>
 * </table>
 */
public final class FtpsConnector implements SourceConnector {

    /**
     * Transport identifier.
     *
     * <p>{@code ftps} rather than {@code ftp}, because that is what a deployment should be using and
     * a source definition is read by people. Plain FTP is a setting on this transport and not a
     * transport of its own, so that choosing it is visibly a downgrade of this one.
     */
    public static final ConnectorType TYPE = ConnectorType.of("ftps");

    static final String SETTING_HOST = "host";
    static final String SETTING_PORT = "port";
    static final String SETTING_USERNAME = "username";
    static final String SETTING_PASSWORD = "password";
    static final String SETTING_SECURITY = "security";
    static final String SETTING_CONNECTION_MODE = "connectionMode";
    static final String SETTING_DIRECTORY = "directory";
    static final String SETTING_FILE_PATTERN = "filePattern";
    static final String SETTING_AFTER_DOWNLOAD = "afterDownload";
    static final String SETTING_ARCHIVE_DIRECTORY = "archiveDirectory";
    static final String SETTING_RETENTION = "retention";
    static final String SETTING_RECORD_MODE = "recordMode";
    static final String SETTING_SKIP_HEADER_LINES = "skipHeaderLines";
    static final String SETTING_CHARSET = "charset";
    static final String SETTING_MAX_FILES = "maxFiles";
    static final String SETTING_TIMEOUT_SECONDS = "timeoutSeconds";

    private static final Set<String> RECOGNISED_SETTINGS = Set.of(
            SETTING_HOST, SETTING_PORT, SETTING_USERNAME, SETTING_PASSWORD, SETTING_SECURITY,
            SETTING_CONNECTION_MODE, SETTING_DIRECTORY, SETTING_FILE_PATTERN, SETTING_AFTER_DOWNLOAD,
            SETTING_ARCHIVE_DIRECTORY, SETTING_RETENTION, SETTING_RECORD_MODE,
            SETTING_SKIP_HEADER_LINES, SETTING_CHARSET, SETTING_MAX_FILES, SETTING_TIMEOUT_SECONDS);

    /** How TLS is established, or declined. */
    enum Security {
        /** Connect in the clear on the control port, then {@code AUTH TLS}. The usual FTPS. */
        EXPLICIT,
        /** TLS from the first byte, historically on port 990. */
        IMPLICIT,
        /** No TLS at all. Credential and records in clear text. */
        NONE
    }

    private final Clock clock;

    private SourceCheckpointStore checkpoints = SourceCheckpointStore.unavailable();
    private ConnectorConfig config;
    private String host;
    private int port;
    private String username;
    private String password;
    private Security security;
    private boolean passive;
    private String directory;
    private PathMatcher filePattern;
    private AfterDownload afterDownload;
    private String archiveDirectory;
    private RetentionPosture retention;
    private RecordMode recordMode;
    private int skipHeaderLines;
    private Charset charset;
    private int maxFiles;
    private int timeoutSeconds;

    public FtpsConnector() {
        this(Clock.systemUTC());
    }

    public FtpsConnector(Clock clock) {
        this.clock = clock;
    }

    @Override
    public ConnectorType type() {
        return TYPE;
    }

    @Override
    public InteractionMode interactionMode() {
        return InteractionMode.POLL;
    }

    /**
     * Whether records pulled from this server may be kept -- configured, never assumed.
     *
     * <p>Same reasoning as the SFTP connector, and the same law behind it (ADR 0027): this transport
     * reaches another organisation's server, and what is sitting on it is as likely to be their
     * response set as this agency's own extract.
     *
     * @throws IllegalStateException before {@code configure}, rather than guessing
     */
    @Override
    public RetentionPosture retention() {
        if (retention == null) {
            throw new IllegalStateException(
                    "FtpsConnector.retention() called before configure(). A pulled directory's "
                            + "retention posture is configuration, not a property of the transport. "
                            + "Set the '" + SETTING_RETENTION + "' setting (ADR 0027).");
        }
        return retention;
    }

    @Override
    public void useCheckpointStore(SourceCheckpointStore store) {
        this.checkpoints = store;
    }

    @Override
    public void configure(ConnectorConfig connectorConfig) {
        connectorConfig.requireOnly(RECOGNISED_SETTINGS);

        List<ConnectorConfigurationException.Problem> problems = new ArrayList<>();

        String configuredHost = connectorConfig.requiredSetting(SETTING_HOST);
        String configuredUsername = connectorConfig.requiredSetting(SETTING_USERNAME);
        String configuredPassword = connectorConfig.requiredSetting(SETTING_PASSWORD);
        String configuredDirectory = connectorConfig.requiredSetting(SETTING_DIRECTORY);

        Security configuredSecurity = readSecurity(connectorConfig, problems);

        int defaultPort = configuredSecurity == Security.IMPLICIT ? 990 : 21;
        int configuredPort = connectorConfig.intSettingOr(SETTING_PORT, defaultPort);
        if (configuredPort < 1 || configuredPort > 65_535) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_PORT,
                    "must be a port number between 1 and 65535, found " + configuredPort));
        }

        String mode = connectorConfig.settingOr(SETTING_CONNECTION_MODE, "passive")
                .toLowerCase(Locale.ROOT);
        boolean configuredPassive = true;
        if (mode.equals("active")) {
            configuredPassive = false;
        } else if (!mode.equals("passive")) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_CONNECTION_MODE,
                    "must be 'passive' or 'active', found '" + mode + "'. Passive is what works "
                            + "through a firewall that does not accept inbound connections, which "
                            + "is nearly always the situation this platform is deployed into"));
        }

        AfterDownload posture = readAfterDownload(connectorConfig, problems);
        RetentionPosture configuredRetention = readRetention(connectorConfig, problems);

        String configuredArchive = connectorConfig.setting(SETTING_ARCHIVE_DIRECTORY).orElse(null);
        if (posture == AfterDownload.ARCHIVE && configuredArchive == null) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_ARCHIVE_DIRECTORY,
                    "is required when '" + SETTING_AFTER_DOWNLOAD + "' is 'archive'; there is "
                            + "nowhere to move a landed file to"));
        }
        if (posture != AfterDownload.ARCHIVE && configuredArchive != null) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_ARCHIVE_DIRECTORY,
                    "is set, but '" + SETTING_AFTER_DOWNLOAD + "' is '" + posture + "', so nothing "
                            + "will ever be moved there. A setting that has no effect is how an "
                            + "operator comes to believe files are being archived when they are not"));
        }

        String declaredRecordMode = connectorConfig.settingOr(SETTING_RECORD_MODE, "line");
        RecordMode configuredRecordMode = RecordMode.parse(declaredRecordMode).orElse(null);
        if (configuredRecordMode == null) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_RECORD_MODE,
                    "must be 'line' or 'file', found '" + declaredRecordMode + "'"));
        }

        Charset configuredCharset = StandardCharsets.UTF_8;
        String charsetName = connectorConfig.settingOr(SETTING_CHARSET, "UTF-8");
        try {
            configuredCharset = Charset.forName(charsetName);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_CHARSET,
                    "is not a charset this JVM supports: '" + charsetName + "'"));
        }

        int configuredSkip = connectorConfig.intSettingOr(SETTING_SKIP_HEADER_LINES, 0);
        if (configuredSkip < 0) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_SKIP_HEADER_LINES,
                    "cannot be negative, found " + configuredSkip));
        }
        int configuredMaxFiles = connectorConfig.intSettingOr(SETTING_MAX_FILES, 1_000);
        if (configuredMaxFiles <= 0) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_MAX_FILES,
                    "must be positive, found " + configuredMaxFiles));
        }
        int configuredTimeout = connectorConfig.intSettingOr(SETTING_TIMEOUT_SECONDS, 30);
        if (configuredTimeout <= 0) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_TIMEOUT_SECONDS,
                    "must be positive, found " + configuredTimeout));
        }

        String pattern = connectorConfig.settingOr(SETTING_FILE_PATTERN, "*");
        PathMatcher configuredPattern = null;
        try {
            configuredPattern = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_FILE_PATTERN,
                    "is not a usable glob: '" + pattern + "'"));
        }

        if (!problems.isEmpty()) {
            throw new ConnectorConfigurationException(
                    connectorConfig.sourceId(), connectorConfig.connectorInstanceId(), problems);
        }

        if (posture.needsCheckpointStore()) {
            checkpoints.requireUsable(
                    connectorConfig.sourceId(), connectorConfig.connectorInstanceId());
        }

        this.config = connectorConfig;
        this.host = configuredHost;
        this.port = configuredPort;
        this.username = configuredUsername;
        this.password = configuredPassword;
        this.security = configuredSecurity;
        this.passive = configuredPassive;
        this.directory = configuredDirectory;
        this.filePattern = configuredPattern;
        this.afterDownload = posture;
        this.archiveDirectory = configuredArchive;
        this.retention = configuredRetention;
        this.recordMode = configuredRecordMode;
        this.skipHeaderLines = configuredSkip;
        this.charset = configuredCharset;
        this.maxFiles = configuredMaxFiles;
        this.timeoutSeconds = configuredTimeout;
    }

    /**
     * How the connection is secured, which must be stated.
     *
     * <p>No default, and specifically no permissive one. Defaulting to {@code explicit} would be the
     * safe guess and would still be wrong: an operator pointing this at a server that does not
     * support TLS would get a connection failure they would resolve by trying settings until one
     * worked, and {@code none} is the one that always works. Making it a required choice puts the
     * decision in the file where it can be reviewed, rather than in the history of what someone
     * tried.
     */
    private Security readSecurity(
            ConnectorConfig connectorConfig, List<ConnectorConfigurationException.Problem> problems) {

        String declared = connectorConfig.setting(SETTING_SECURITY).orElse(null);
        if (declared == null) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_SECURITY,
                    "is required and has no default. 'explicit' is ordinary FTPS -- connect, then "
                            + "AUTH TLS. 'implicit' is TLS from the first byte, historically on port "
                            + "990. 'none' is plain FTP: the credential and every record cross the "
                            + "network in clear text, which is occasionally defensible on a private "
                            + "circuit and never defensible by accident (ADR 0033)"));
            return null;
        }
        return switch (declared.toLowerCase(Locale.ROOT)) {
            case "explicit" -> Security.EXPLICIT;
            case "implicit" -> Security.IMPLICIT;
            case "none" -> Security.NONE;
            default -> {
                problems.add(new ConnectorConfigurationException.Problem(SETTING_SECURITY,
                        "must be 'explicit', 'implicit' or 'none', found '" + declared + "'"));
                yield null;
            }
        };
    }

    private AfterDownload readAfterDownload(
            ConnectorConfig connectorConfig, List<ConnectorConfigurationException.Problem> problems) {

        String declared = connectorConfig.setting(SETTING_AFTER_DOWNLOAD).orElse(null);
        if (declared == null) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_AFTER_DOWNLOAD,
                    "is required and has no default. A remote directory does not remember that "
                            + "anyone read it, so this says who does: 'archive' or 'delete' (the "
                            + "remote server remembers, and this connector needs write access), "
                            + "'watermark' (this platform remembers, and a file stamped older than "
                            + "the last one landed will be skipped), or 'none' (nobody remembers; "
                            + "every run re-reads and bronze shows the duplicates). Each is right "
                            + "somewhere and no default is right everywhere (ADR 0031)"));
            return null;
        }
        Optional<AfterDownload> parsed = AfterDownload.parse(declared);
        if (parsed.isEmpty()) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_AFTER_DOWNLOAD,
                    "must be 'archive', 'delete', 'watermark' or 'none', found '" + declared + "'"));
        }
        return parsed.orElse(null);
    }

    private RetentionPosture readRetention(
            ConnectorConfig connectorConfig, List<ConnectorConfigurationException.Problem> problems) {

        String declared = connectorConfig.setting(SETTING_RETENTION).orElse(null);
        if (declared == null) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_RETENTION,
                    "is required and has no default. A pulled directory may hold this agency's own "
                            + "extract ('retained') or another system's non-retainable response set "
                            + "('transient'), and the transport cannot tell which (ADR 0027)"));
            return null;
        }
        return switch (declared.toLowerCase(Locale.ROOT)) {
            case "retained" -> RetentionPosture.RETAINED;
            case "transient" -> RetentionPosture.TRANSIENT;
            default -> {
                problems.add(new ConnectorConfigurationException.Problem(SETTING_RETENTION,
                        "must be 'retained' or 'transient', found '" + declared + "'"));
                yield null;
            }
        };
    }

    @Override
    public SourceHandle open() {
        requireConfigured();
        FtpConnection connection = connect();
        try {
            List<RemoteFile> due = filesDue(connection);
            return new PullSourceHandle(
                    connection, due, directory, archiveDirectory, afterDownload, checkpoints,
                    recordMode, skipHeaderLines, charset, config.sourceId(),
                    config.connectorInstanceId(), clock);
        } catch (IOException e) {
            connection.close();
            throw new UncheckedIOException("Cannot list " + directory + " on " + host, e);
        } catch (RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    private List<RemoteFile> filesDue(FtpConnection connection) throws IOException {
        List<RemoteFile> matching = connection.list(directory, filePattern);
        List<RemoteFile> due = new ArrayList<>(matching.size());
        String watermark = storedWatermark().orElse(null);
        for (RemoteFile file : matching) {
            if (watermark != null && !file.isAfter(watermark)) {
                continue;
            }
            due.add(file);
            if (due.size() == maxFiles) {
                break;
            }
        }
        return due;
    }

    private Optional<String> storedWatermark() {
        if (afterDownload != AfterDownload.WATERMARK) {
            return Optional.empty();
        }
        return checkpoints.read(config.sourceId(), config.connectorInstanceId())
                .map(SourceCheckpoint::position);
    }

    private FtpConnection connect() {
        FTPClient client = switch (security) {
            case EXPLICIT -> new FTPSClient(false);
            case IMPLICIT -> new FTPSClient(true);
            case NONE -> new FTPClient();
        };
        int millis = timeoutSeconds * 1_000;
        client.setConnectTimeout(millis);
        client.setDefaultTimeout(millis);
        try {
            client.connect(host, port);
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
                String reply = client.getReplyString().trim();
                client.disconnect();
                throw new IOException("the server refused the connection: " + reply);
            }
            client.setDataTimeout(java.time.Duration.ofMillis(millis));

            if (client instanceof FTPSClient secured && security == Security.EXPLICIT) {
                secured.execAUTH("TLS");
            }
            if (!client.login(username, password)) {
                String reply = client.getReplyString().trim();
                client.disconnect();
                // The reply, never the credential. A failed login reply routinely echoes the user.
                throw new IOException("authentication was refused: " + reply);
            }
            if (client instanceof FTPSClient secured) {
                // Without this the data connection is opened in the clear even though the control
                // connection is protected -- so the credential is safe and every record is not,
                // which is the failure mode nobody notices because the transfer works.
                secured.execPBSZ(0);
                secured.execPROT("P");
            }
            // Binary, always. ASCII mode rewrites line endings in flight, which alters the bytes
            // bronze hashes and makes the same file land with two identities.
            client.setFileType(FTP.BINARY_FILE_TYPE);
            if (passive) {
                client.enterLocalPassiveMode();
            } else {
                client.enterLocalActiveMode();
            }
            return new FtpConnection(client);
        } catch (IOException e) {
            closeQuietly(client);
            throw new UncheckedIOException(
                    "Cannot open an " + transportName() + " session to " + host + ":" + port, e);
        } catch (RuntimeException e) {
            closeQuietly(client);
            throw e;
        }
    }

    private String transportName() {
        return security == Security.NONE ? "FTP" : "FTPS";
    }

    private static void closeQuietly(FTPClient client) {
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
        } catch (IOException ignored) {
            // Already failing; this is cleanup.
        }
    }

    @Override
    public HealthStatus health() {
        Instant now = clock.instant();
        if (config == null) {
            return HealthStatus.notConfigured(now);
        }
        try (FtpConnection connection = connect()) {
            if (!connection.isDirectory(directory)) {
                return HealthStatus.unavailable(
                        "the remote directory " + directory + " is not there, or not listable by "
                                + username, now);
            }
            if (afterDownload == AfterDownload.ARCHIVE && !connection.isDirectory(archiveDirectory)) {
                return HealthStatus.degraded(
                        "the archive directory " + archiveDirectory + " is not there; records "
                                + "would land and then fail to be archived", now);
            }
            return HealthStatus.healthy(now);
        } catch (UncheckedIOException e) {
            return HealthStatus.unavailable(
                    "cannot reach " + host + ":" + port + ": " + rootMessage(e), now);
        } catch (IOException | RuntimeException e) {
            return HealthStatus.unavailable(
                    "cannot probe " + host + ":" + port + ": " + rootMessage(e), now);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    @Override
    public void close() {
        // Nothing held open between reads: a session's life is one open() pull.
    }

    private void requireConfigured() {
        if (config == null) {
            throw new IllegalStateException("FtpsConnector.open() called before configure()");
        }
    }

    /**
     * Only what an operator needs, and no credential.
     *
     * <p>The security posture is printed because it is the one setting whose wrong value is both
     * invisible in operation and serious, and an operator reading a log line is entitled to see
     * that this session is in the clear.
     */
    @Override
    public String toString() {
        return "FtpsConnector[" + (host == null ? "<unconfigured>" : username + "@" + host + ":" + port)
                + ", security=" + (security == null ? "<undeclared>" : security.name().toLowerCase(Locale.ROOT))
                + ", directory=" + directory
                + ", afterDownload=" + (afterDownload == null ? "<undeclared>" : afterDownload)
                + ", retention=" + (retention == null ? "<undeclared>" : retention) + "]";
    }
}
