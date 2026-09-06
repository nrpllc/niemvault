package gov.niemplatform.connectors.file;

import gov.niemplatform.connectors.api.ConnectorConfig;
import gov.niemplatform.connectors.api.ConnectorConfigurationException;
import gov.niemplatform.connectors.api.ConnectorType;
import gov.niemplatform.connectors.api.HealthStatus;
import gov.niemplatform.connectors.api.SourceConnector;
import gov.niemplatform.connectors.api.SourceHandle;
import gov.niemplatform.storage.api.RawEnvelope;
import gov.niemplatform.storage.api.SourceOffset;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Lands files dropped into a directory (spec §4.3). The only connector in Phase 1.
 *
 * <p>Reads nothing about the shape of the data beyond how to cut it into records. Parsing the CSV,
 * splitting the packed name field, normalising the date -- none of that happens here. That is
 * schema mapping, the other half of source onboarding, and keeping it out is what lets the same
 * mapping serve a future Kafka or CDC feed of the same records.
 *
 * <h2>Settings</h2>
 *
 * <table>
 *   <caption>Transport settings</caption>
 *   <tr><th>Key</th><th>Meaning</th></tr>
 *   <tr><td>{@code directory}</td><td>Required. Directory to read.</td></tr>
 *   <tr><td>{@code filePattern}</td><td>Glob of files to pick up. Default {@code *}.</td></tr>
 *   <tr><td>{@code recordMode}</td><td>{@code line} (default) or {@code file}.</td></tr>
 *   <tr><td>{@code skipHeaderLines}</td><td>Header rows to skip per file. Default {@code 0}.</td></tr>
 *   <tr><td>{@code charset}</td><td>Only used to decode line boundaries. Default {@code UTF-8}.</td></tr>
 * </table>
 *
 * <h2>What this connector does not do in Phase 1</h2>
 *
 * <p>It does not archive, move, or delete files, and it does not remember what it has already
 * read. Opening it twice over the same directory lands the same records twice. That is a
 * deliberate Phase 1 boundary rather than an oversight: source offsets are stable
 * ({@code <file>#<line>}) and envelope identity is derived from source, offset, and content hash,
 * so duplicates are exactly detectable and de-duplication can be added without re-landing
 * anything. Operators drive it a directory at a time through the CLI.
 */
public final class FileDropConnector implements SourceConnector {

    /** Transport identifier, as used in connector configuration. */
    public static final ConnectorType TYPE = ConnectorType.of("file-drop");

    static final String SETTING_DIRECTORY = "directory";
    static final String SETTING_FILE_PATTERN = "filePattern";
    static final String SETTING_RECORD_MODE = "recordMode";
    static final String SETTING_SKIP_HEADER_LINES = "skipHeaderLines";
    static final String SETTING_CHARSET = "charset";

    private static final Set<String> RECOGNISED_SETTINGS = Set.of(
            SETTING_DIRECTORY, SETTING_FILE_PATTERN, SETTING_RECORD_MODE,
            SETTING_SKIP_HEADER_LINES, SETTING_CHARSET);

    /** How a file is cut into records. */
    enum RecordMode {
        /** One record per line, the usual shape for a CSV export. */
        LINE,
        /** One record per file, for formats that are a single document. */
        FILE
    }

    private final Clock clock;

    private ConnectorConfig config;
    private Path directory;
    private String filePattern;
    private RecordMode recordMode;
    private int skipHeaderLines;
    private Charset charset;

    public FileDropConnector() {
        this(Clock.systemUTC());
    }

    public FileDropConnector(Clock clock) {
        this.clock = clock;
    }

    @Override
    public ConnectorType type() {
        return TYPE;
    }

    @Override
    public void configure(ConnectorConfig connectorConfig) {
        connectorConfig.requireOnly(RECOGNISED_SETTINGS);

        List<ConnectorConfigurationException.Problem> problems = new ArrayList<>();

        Path configuredDirectory = connectorConfig.pathSetting(SETTING_DIRECTORY);
        if (!Files.isDirectory(configuredDirectory)) {
            problems.add(new ConnectorConfigurationException.Problem(SETTING_DIRECTORY,
                    "is not an existing directory"));
        }

        String mode = connectorConfig.settingOr(SETTING_RECORD_MODE, "line").toLowerCase(java.util.Locale.ROOT);
        RecordMode configuredMode = switch (mode) {
            case "line" -> RecordMode.LINE;
            case "file" -> RecordMode.FILE;
            default -> {
                problems.add(new ConnectorConfigurationException.Problem(SETTING_RECORD_MODE,
                        "must be 'line' or 'file', found '" + mode + "'"));
                yield null;
            }
        };

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

        if (!problems.isEmpty()) {
            throw new ConnectorConfigurationException(
                    connectorConfig.sourceId(), connectorConfig.connectorInstanceId(), problems);
        }

        this.config = connectorConfig;
        this.directory = configuredDirectory;
        this.filePattern = connectorConfig.settingOr(SETTING_FILE_PATTERN, "*");
        this.recordMode = configuredMode;
        this.skipHeaderLines = configuredSkip;
        this.charset = configuredCharset;
    }

    @Override
    public SourceHandle open() {
        requireConfigured();
        List<Path> files = matchingFiles();
        Stream<RawEnvelope> envelopes = files.stream().flatMap(this::envelopesFrom);
        return new SourceHandle() {

            @Override
            public Stream<RawEnvelope> envelopes() {
                return envelopes;
            }

            @Override
            public void close() {
                envelopes.close();
            }
        };
    }

    private List<Path> matchingFiles() {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, filePattern)) {
            stream.forEach(path -> {
                if (Files.isRegularFile(path)) {
                    files.add(path);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + directory + " for pattern " + filePattern, e);
        }
        // Sorted so a run is reproducible: directory iteration order is not guaranteed, and
        // replay comparisons depend on landing order being the same every time.
        files.sort(java.util.Comparator.comparing(path -> path.getFileName().toString()));
        return files;
    }

    private Stream<RawEnvelope> envelopesFrom(Path file) {
        Instant asserted = lastModified(file);
        Instant ingested = clock.instant();

        if (recordMode == RecordMode.FILE) {
            byte[] payload;
            try {
                payload = Files.readAllBytes(file);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + file, e);
            }
            return Stream.of(new RawEnvelope(
                    config.sourceId(), config.connectorInstanceId(), ingested, asserted, payload,
                    SourceOffset.of(file.getFileName().toString())));
        }

        Stream<String> lines;
        try {
            lines = Files.lines(file, charset);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }

        String fileName = file.getFileName().toString();
        AtomicInteger lineNumber = new AtomicInteger();
        // flatMap closes this inner stream once drained, which releases the file handle.
        return lines
                .map(line -> new NumberedLine(lineNumber.incrementAndGet(), line))
                .filter(numbered -> numbered.number() > skipHeaderLines)
                .filter(numbered -> !numbered.text().isBlank())
                .map(numbered -> new RawEnvelope(
                        config.sourceId(),
                        config.connectorInstanceId(),
                        ingested,
                        asserted,
                        numbered.text().getBytes(charset),
                        // Zero-padded so lexicographic offset order matches file order.
                        SourceOffset.of("%s#%06d".formatted(fileName, numbered.number()))));
    }

    private record NumberedLine(int number, String text) {}

    /**
     * The file's modification time, used as the source-asserted timestamp.
     *
     * <p>Batch-granular, not per-record: every record from one file shares it. It is the most
     * specific assertion a file drop can make -- the source system wrote this file at this moment
     * -- and it is what makes {@code PipelineLag} (§4.7) meaningful for file sources. A per-record
     * timestamp exists only inside the payload, and extracting it is the mapping's job, not this
     * connector's.
     */
    private Instant lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the modification time of " + file, e);
        }
    }

    @Override
    public HealthStatus health() {
        Instant now = clock.instant();
        if (config == null) {
            return HealthStatus.notConfigured(now);
        }
        if (!Files.isDirectory(directory)) {
            return HealthStatus.unavailable(
                    "drop directory " + directory + " no longer exists", now);
        }
        if (!Files.isReadable(directory)) {
            return HealthStatus.unavailable("drop directory " + directory + " is not readable", now);
        }
        return HealthStatus.healthy(now);
    }

    @Override
    public void close() {
        // Nothing held open between reads: file handles are scoped to an open() stream.
    }

    private void requireConfigured() {
        if (config == null) {
            throw new IllegalStateException(
                    "FileDropConnector.open() called before configure()");
        }
    }
}
