package gov.niemplatform.connectors.sftp;

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
import gov.niemplatform.connectors.pull.RemoteDirectory;
import gov.niemplatform.connectors.pull.RemoteFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

/**
 * Pulls files from a remote directory over SFTP and lands them (spec §4.3, ADR 0031).
 *
 * <p>The transport an agency actually gets offered when a state system will not publish to a topic
 * and will not let anyone near its database: a directory on a server, a credential, and an export
 * written into it on a schedule. Like every connector it knows nothing about the shape of what it
 * carries -- the same {@code cad-to-canonical} mapping serves this source, the nightly file drop and
 * the Kafka topic, which is the separation §4.3 draws between transport configuration and schema
 * mapping.
 *
 * <h2>What a pull has to answer that a push does not</h2>
 *
 * <p>A remote directory does not remember that anyone read it. Kafka handed that problem to a
 * consumer group and ADR 0028 simply advanced it; here the operator has to say who remembers, which
 * is the {@code afterDownload} setting and the reason it has no default. See {@link AfterDownload}:
 * the remote server can remember by having the file moved or deleted, this platform can remember a
 * watermark, or nobody remembers and every run re-reads. They differ in what access they need and in
 * what they can get wrong, and none of them is the obvious default.
 *
 * <h2>Settings</h2>
 *
 * <table>
 *   <caption>Transport settings</caption>
 *   <tr><th>Key</th><th>Meaning</th></tr>
 *   <tr><td>{@code host}</td><td>Required.</td></tr>
 *   <tr><td>{@code port}</td><td>Default {@code 22}.</td></tr>
 *   <tr><td>{@code username}</td><td>Required.</td></tr>
 *   <tr><td>{@code password}</td><td>One of this or {@code privateKeyPath}. Never logged.</td></tr>
 *   <tr><td>{@code privateKeyPath}</td><td>One of this or {@code password}.</td></tr>
 *   <tr><td>{@code privateKeyPassphrase}</td><td>Optional. Never logged.</td></tr>
 *   <tr><td>{@code knownHostsPath}</td><td>Host key verification, one of three. </td></tr>
 *   <tr><td>{@code hostKeyFingerprint}</td><td>e.g. {@code SHA256:...}.</td></tr>
 *   <tr><td>{@code hostKeyCheck}</td><td>{@code accept-any} to disable, explicitly.</td></tr>
 *   <tr><td>{@code directory}</td><td>Required. Remote directory to pull from.</td></tr>
 *   <tr><td>{@code filePattern}</td><td>Glob. Default {@code *}.</td></tr>
 *   <tr><td>{@code afterDownload}</td><td>Required, no default. {@code archive}, {@code delete}, {@code watermark} or {@code none}.</td></tr>
 *   <tr><td>{@code archiveDirectory}</td><td>Required when {@code afterDownload} is {@code archive}.</td></tr>
 *   <tr><td>{@code retention}</td><td>Required, no default. {@code retained} or {@code transient}.</td></tr>
 *   <tr><td>{@code recordMode}</td><td>{@code line} (default) or {@code file}.</td></tr>
 *   <tr><td>{@code skipHeaderLines}</td><td>Default {@code 0}.</td></tr>
 *   <tr><td>{@code charset}</td><td>Default {@code UTF-8}.</td></tr>
 *   <tr><td>{@code maxFiles}</td><td>Ceiling on one pull. Default {@code 1000}.</td></tr>
 *   <tr><td>{@code timeoutSeconds}</td><td>Connect and authenticate timeout. Default {@code 30}.</td></tr>
 * </table>
 */
public final class SftpConnector implements SourceConnector {

    /** Transport identifier, as used in connector configuration. */
    public static final ConnectorType TYPE = ConnectorType.of("sftp");

    static final String SETTING_HOST = "host";
    static final String SETTING_PORT = "port";
    static final String SETTING_USERNAME = "username";
    static final String SETTING_PASSWORD = "password";
    static final String SETTING_PRIVATE_KEY_PATH = "privateKeyPath";
    static final String SETTING_PRIVATE_KEY_PASSPHRASE = "privateKeyPassphrase";
    static final String SETTING_KNOWN_HOSTS_PATH = "knownHostsPath";
    static final String SETTING_HOST_KEY_FINGERPRINT = "hostKeyFingerprint";
    static final String SETTING_HOST_KEY_CHECK = "hostKeyCheck";
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
            SETTING_HOST, SETTING_PORT, SETTING_USERNAME, SETTING_PASSWORD,
            SETTING_PRIVATE_KEY_PATH, SETTING_PRIVATE_KEY_PASSPHRASE, SETTING_KNOWN_HOSTS_PATH,
            SETTING_HOST_KEY_FINGERPRINT, SETTING_HOST_KEY_CHECK, SETTING_DIRECTORY,
            SETTING_FILE_PATTERN, SETTING_AFTER_DOWNLOAD, SETTING_ARCHIVE_DIRECTORY,
            SETTING_RETENTION, SETTING_RECORD_MODE, SETTING_SKIP_HEADER_LINES, SETTING_CHARSET,
            SETTING_MAX_FILES, SETTING_TIMEOUT_SECONDS);

    private final Clock clock;

    private SourceCheckpointStore checkpoints = SourceCheckpointStore.unavailable();
    private ConnectorConfig config;
    private String host;
    private int port;
    private String username;
    private String password;
    private Path privateKeyPath;
    private String privateKeyPassphrase;
    private ServerKeyVerifier hostKeyVerifier;
    private String directory;
    private PathMatcher filePattern;
    private AfterDownload afterDownload;
    private String archiveDirectory;
    private RetentionPosture retention;
    private RecordMode recordMode;
    private int skipHeaderLines;
    private Charset charset;
    private int maxFiles;
    private Duration timeout;

    public SftpConnector() {
        this(Clock.systemUTC());
    }

    public SftpConnector(Clock clock) {
        this.clock = clock;
    }

    @Override
    public ConnectorType type() {
        return TYPE;
    }

    @Override
    public InteractionMode interactionMode() {
        // The platform chooses when to look. The source wrote a file whenever it wrote it and has
        // no idea anyone is reading -- which is what POLL means, and is why the freshness SLA on a
        // pull is measured against the file's modification time rather than against the run.
        return InteractionMode.POLL;
    }

    /**
     * Whether records pulled from this server may be kept -- configured, never assumed.
     *
     * <p>The file-drop connector can answer from the transport alone: a file an agency placed in its
     * own drop directory is its own data. A pull cannot, and for a sharper reason than Kafka's. This
     * connector exists to reach <em>somebody else's</em> server, and the export sitting on it is as
     * likely to be a state system's response set, held under that state's rules, as it is to be the
     * agency's own extract (ADR 0027).
     *
     * @throws IllegalStateException before {@code configure}, rather than guessing
     */
    @Override
    public RetentionPosture retention() {
        if (retention == null) {
            throw new IllegalStateException(
                    "SftpConnector.retention() called before configure(). A pulled directory's "
                            + "retention posture is configuration, not a property of the transport: "
                            + "this connector reaches another organisation's server, and what is "
                            + "sitting there may be their response set rather than this agency's "
                            + "own extract. Set the '" + SETTING_RETENTION + "' setting (ADR 0027).");
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
        String configuredDirectory = connectorConfig.requiredSetting(SETTING_DIRECTORY);

        int configuredPort = connectorConfig.intSettingOr(SETTING_PORT, 22);
        if (configuredPort < 1 || configuredPort > 65_535) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_PORT,
                    "must be a port number between 1 and 65535, found " + configuredPort));
        }

        Credential credential = readCredential(connectorConfig, problems);
        ServerKeyVerifier verifier = readHostKeyVerification(connectorConfig, problems);
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

        RecordMode configuredRecordMode = RecordMode
                .parse(connectorConfig.settingOr(SETTING_RECORD_MODE, "line"))
                .orElse(null);
        if (configuredRecordMode == null) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_RECORD_MODE,
                    "must be 'line' or 'file', found '"
                            + connectorConfig.settingOr(SETTING_RECORD_MODE, "line") + "'"));
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
        int configuredTimeout = connectorConfig.intSettingOr(
                SETTING_TIMEOUT_SECONDS, (int) SftpConnection.DEFAULT_TIMEOUT.toSeconds());
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

        // Asked once the settings are known to be well-formed, and before anything is opened. A
        // 'watermark' pull with nowhere to write its position would otherwise run perfectly and
        // re-land the whole directory every night, reporting success each time (ADR 0030).
        if (posture.needsCheckpointStore()) {
            checkpoints.requireUsable(
                    connectorConfig.sourceId(), connectorConfig.connectorInstanceId());
        }

        this.config = connectorConfig;
        this.host = configuredHost;
        this.port = configuredPort;
        this.username = configuredUsername;
        this.password = credential.password();
        this.privateKeyPath = credential.privateKeyPath();
        this.privateKeyPassphrase = credential.passphrase();
        this.hostKeyVerifier = verifier;
        this.directory = configuredDirectory;
        this.filePattern = configuredPattern;
        this.afterDownload = posture;
        this.archiveDirectory = configuredArchive;
        this.retention = configuredRetention;
        this.recordMode = configuredRecordMode;
        this.skipHeaderLines = configuredSkip;
        this.charset = configuredCharset;
        this.maxFiles = configuredMaxFiles;
        this.timeout = Duration.ofSeconds(configuredTimeout);
    }

    /** A password or a private key, exactly one of them. */
    private record Credential(String password, Path privateKeyPath, String passphrase) {}

    private Credential readCredential(
            ConnectorConfig connectorConfig, List<ConnectorConfigurationException.Problem> problems) {

        Optional<String> declaredPassword = connectorConfig.setting(SETTING_PASSWORD);
        Optional<String> declaredKey = connectorConfig.setting(SETTING_PRIVATE_KEY_PATH);

        if (declaredPassword.isEmpty() && declaredKey.isEmpty()) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_PASSWORD,
                    "one of '" + SETTING_PASSWORD + "' or '" + SETTING_PRIVATE_KEY_PATH
                            + "' is required; there is no anonymous SFTP"));
            return new Credential(null, null, null);
        }
        if (declaredPassword.isPresent() && declaredKey.isPresent()) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_PRIVATE_KEY_PATH,
                    "cannot be set alongside '" + SETTING_PASSWORD + "'. Which one authenticated "
                            + "would depend on what the server offered, and an operator rotating "
                            + "the one that is not in use would see nothing change"));
            return new Credential(null, null, null);
        }
        // Whether the key file is actually there is deliberately not checked here, for the reason
        // FileDropConnector gives about its drop directory: configuring asks whether the
        // configuration is well-formed, and whether the source can be reached is health()'s
        // question. A records manager reviewing this definition on their own laptop has no
        // /etc/niem/secrets, and a catalogue that refused to describe the source for that reason
        // would be unusable exactly where it is most useful.
        Path keyPath = declaredKey.map(Path::of).orElse(null);
        return new Credential(
                declaredPassword.orElse(null),
                keyPath,
                connectorConfig.setting(SETTING_PRIVATE_KEY_PASSPHRASE).orElse(null));
    }

    /**
     * How the server's identity is checked, which must be stated.
     *
     * <p>No default, and specifically no permissive default. An unverified host key means the
     * session can be terminated by anything that can answer on that address, and what is being
     * handed over is a credential to another agency's system followed by whatever that system
     * exports. "Accept any" remains available, because a closed lab network is real, but it is a
     * sentence an operator has to write down.
     */
    private ServerKeyVerifier readHostKeyVerification(
            ConnectorConfig connectorConfig, List<ConnectorConfigurationException.Problem> problems) {

        Optional<String> knownHosts = connectorConfig.setting(SETTING_KNOWN_HOSTS_PATH);
        Optional<String> fingerprint = connectorConfig.setting(SETTING_HOST_KEY_FINGERPRINT);
        Optional<String> check = connectorConfig.setting(SETTING_HOST_KEY_CHECK);

        long declared = List.of(knownHosts, fingerprint, check).stream()
                .filter(Optional::isPresent).count();
        if (declared == 0) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_HOST_KEY_FINGERPRINT,
                    "the server's identity must be verified: set '" + SETTING_HOST_KEY_FINGERPRINT
                            + "', or '" + SETTING_KNOWN_HOSTS_PATH + "', or '"
                            + SETTING_HOST_KEY_CHECK + ": accept-any' to state that this deployment "
                            + "does not check. Without one, this connector hands a credential and "
                            + "then pulls records from whatever answers on that address"));
            return null;
        }
        if (declared > 1) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_HOST_KEY_CHECK,
                    "only one of '" + SETTING_HOST_KEY_FINGERPRINT + "', '"
                            + SETTING_KNOWN_HOSTS_PATH + "' and '" + SETTING_HOST_KEY_CHECK
                            + "' may be set"));
            return null;
        }
        if (check.isPresent()) {
            if (!check.get().toLowerCase(Locale.ROOT).equals("accept-any")) {
                problems.add(new ConnectorConfigurationException.Problem(SETTING_HOST_KEY_CHECK,
                        "accepts only 'accept-any', found '" + check.get() + "'. To verify a "
                                + "specific server use '" + SETTING_HOST_KEY_FINGERPRINT + "' or '"
                                + SETTING_KNOWN_HOSTS_PATH + "'"));
                return null;
            }
            return AcceptAllServerKeyVerifier.INSTANCE;
        }
        if (knownHosts.isPresent()) {
            // Not checked for existence here either. Same boundary as the private key above: an
            // absent known-hosts file makes the source unreachable, not the definition malformed.
            return new KnownHostsServerKeyVerifier(
                    RejectAllServerKeyVerifier.INSTANCE, Path.of(knownHosts.get()));
        }
        String expected = fingerprint.get();
        return (session, address, key) -> KeyUtils.checkFingerPrint(expected, key).getKey();
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
                            + "('transient'), and the transport cannot tell which. Defaulting would "
                            + "land data that may not lawfully be kept (ADR 0027)"));
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

    /**
     * Connects, lists what is due, and returns a handle over it.
     *
     * <p>The file list is taken once, here. Files that land in the directory during the read belong
     * to the next run, which is the same bounded-slice reasoning ADR 0028 applied to a topic: a read
     * that kept noticing new work would never return, and {@code LandingService} drains a handle to
     * completion.
     */
    @Override
    public SourceHandle open() {
        requireConfigured();
        SftpConnection connection = connect();
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

    /**
     * What this run will read: matching files, minus anything already landed, capped.
     *
     * <p>The cap is applied last and after ordering, so a large first pull is drained over
     * successive runs in read order rather than an arbitrary subset of it. Truncating before
     * ordering would leave a watermark that skipped everything the cut discarded.
     */
    private List<RemoteFile> filesDue(SftpConnection connection) throws IOException {
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

    private SftpConnection connect() {
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(hostKeyVerifier);
        if (privateKeyPath != null) {
            FileKeyPairProvider keys = new FileKeyPairProvider(privateKeyPath);
            if (privateKeyPassphrase != null) {
                keys.setPasswordFinder(FilePasswordProvider.of(privateKeyPassphrase));
            }
            client.setKeyIdentityProvider(keys);
        }
        client.start();
        ClientSession session = null;
        try {
            session = client.connect(username, host, port).verify(timeout).getSession();
            if (password != null) {
                session.addPasswordIdentity(password);
            }
            session.auth().verify(timeout);
            SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
            return new SftpConnection(client, session, sftp);
        } catch (IOException e) {
            closeQuietly(session);
            client.stop();
            // The message, never the settings: a failure here routinely quotes what was sent.
            throw new UncheckedIOException(
                    "Cannot open an SFTP session to " + username + "@" + host + ":" + port, e);
        } catch (RuntimeException e) {
            closeQuietly(session);
            client.stop();
            throw e;
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Already failing; this is cleanup.
        }
    }

    /**
     * Whether the server is reachable, authenticates, and has the directory.
     *
     * <p>Distinct from whether records are flowing: an empty drop directory is healthy and idle. A
     * directory that is not there is not, and so is an archive directory that is missing when
     * {@code afterDownload} is {@code archive} -- that one only fails at acknowledgement time
     * otherwise, which is after records are already in bronze.
     */
    @Override
    public HealthStatus health() {
        Instant now = clock.instant();
        if (config == null) {
            return HealthStatus.notConfigured(now);
        }
        // Checked here rather than at configuration time, and worth checking at all: an absent key
        // file otherwise surfaces as a generic authentication failure, which sends an operator to
        // the far side's account administration for a problem that is on this side's volume mount.
        if (privateKeyPath != null && !Files.isReadable(privateKeyPath)) {
            return HealthStatus.unavailable(
                    "the private key " + privateKeyPath + " is not readable; this is a mount or a "
                            + "permission on this side, not a credential the far side rejected", now);
        }
        try (SftpConnection connection = connect()) {
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
            throw new IllegalStateException("SftpConnector.open() called before configure()");
        }
    }

    /** Only what an operator needs. No credential, and no host key. */
    @Override
    public String toString() {
        return "SftpConnector[" + (host == null ? "<unconfigured>" : username + "@" + host + ":" + port)
                + ", directory=" + directory
                + ", afterDownload=" + (afterDownload == null ? "<undeclared>" : afterDownload)
                + ", retention=" + (retention == null ? "<undeclared>" : retention) + "]";
    }
}
