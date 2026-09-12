package gov.niemplatform.connectors.api;

import java.io.IOException;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * A checkpoint store backed by one small file per connector instance (ADR 0030).
 *
 * <p>Files rather than a database, because of where this has to run. Spec §6 describes an
 * air-gapped delivery mode and §4.3 a connector shipped as a jar; a position store that required a
 * schema migration in somebody's Postgres would make adding a transport an operations project. A
 * directory works in a container with a mounted volume, on a laptop, and in an install with no
 * database at all, and an operator can read the contents with {@code cat} during an incident --
 * which is exactly when they want to know how far a source got.
 *
 * <h2>Writes are atomic or they do not happen</h2>
 *
 * <p>A position is written to a temporary file in the same directory, forced to disk, and then
 * moved onto the real name in one step. A crash mid-write therefore leaves either the previous
 * position or the new one, never half of either. A truncated position file would be worse than no
 * file: absent means "start from the beginning", which is safe and merely duplicative, while
 * corrupt could be read as a position further ahead than anything that actually landed.
 *
 * <p>For that reason a file that cannot be parsed is an error rather than a silent restart from
 * zero. Re-reading a source from the beginning is a decision an operator makes with
 * {@link #clear}; it is not something a damaged file gets to decide on their behalf.
 */
public final class FileSourceCheckpointStore implements SourceCheckpointStore {

    private static final String EXTENSION = ".checkpoint.yaml";
    private static final String KEY_SOURCE_ID = "sourceId";
    private static final String KEY_INSTANCE_ID = "connectorInstanceId";
    private static final String KEY_POSITION = "position";
    private static final String KEY_RECORDED_AT = "recordedAt";

    private final Path directory;

    private FileSourceCheckpointStore(Path directory) {
        this.directory = directory;
    }

    /**
     * Opens a store under a directory, creating it if it does not exist.
     *
     * @throws CheckpointStoreException if the directory cannot be created or is not writable
     */
    public static FileSourceCheckpointStore under(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new CheckpointStoreException(
                    "Cannot create the checkpoint directory " + directory, e);
        }
        if (!Files.isWritable(directory)) {
            throw new CheckpointStoreException(
                    "The checkpoint directory " + directory + " is not writable. A connector that "
                            + "cannot record its position would re-read its source from the "
                            + "beginning on every run (ADR 0030)", null);
        }
        return new FileSourceCheckpointStore(directory);
    }

    /** The directory positions are kept in. */
    public Path directory() {
        return directory;
    }

    @Override
    public Optional<SourceCheckpoint> read(String sourceId, String connectorInstanceId) {
        Path file = fileFor(sourceId, connectorInstanceId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        SourceCheckpoint checkpoint = parse(file);
        if (!checkpoint.belongsTo(sourceId, connectorInstanceId)) {
            // Only reachable if two different instances hashed to one filename or a file was moved
            // by hand. Either way, reading it would hand a connector somebody else's position.
            throw new CheckpointStoreException(
                    file + " holds the position of '" + checkpoint.connectorInstanceId()
                            + "' (source '" + checkpoint.sourceId() + "'), not of '"
                            + connectorInstanceId + "' (source '" + sourceId + "')", null);
        }
        return Optional.of(checkpoint);
    }

    @Override
    public void write(SourceCheckpoint checkpoint) {
        Path file = fileFor(checkpoint.sourceId(), checkpoint.connectorInstanceId());
        Map<String, Object> document = new LinkedHashMap<>();
        document.put(KEY_SOURCE_ID, checkpoint.sourceId());
        document.put(KEY_INSTANCE_ID, checkpoint.connectorInstanceId());
        document.put(KEY_POSITION, checkpoint.position());
        document.put(KEY_RECORDED_AT, checkpoint.recordedAt().toString());

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        String rendered = new Yaml(options).dump(document);

        Path temporary;
        try {
            temporary = Files.createTempFile(directory, "checkpoint", ".tmp");
        } catch (IOException e) {
            throw new CheckpointStoreException("Cannot write a checkpoint into " + directory, e);
        }
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.write(rendered);
            }
            // Forced before the move, not after. A move that beats the data to disk leaves a file
            // that exists and is empty, which is the one outcome atomicity is here to prevent.
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            move(temporary, file);
        } catch (IOException e) {
            deleteQuietly(temporary);
            throw new CheckpointStoreException("Cannot write the checkpoint " + file, e);
        } catch (RuntimeException e) {
            deleteQuietly(temporary);
            throw e;
        }
    }

    @Override
    public void clear(String sourceId, String connectorInstanceId) {
        Path file = fileFor(sourceId, connectorInstanceId);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new CheckpointStoreException("Cannot clear the checkpoint " + file, e);
        }
    }

    @Override
    public List<SourceCheckpoint> readAll() {
        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(EXTENSION))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new CheckpointStoreException("Cannot list the checkpoint directory " + directory, e);
        }
        List<SourceCheckpoint> checkpoints = new ArrayList<>(files.size());
        files.forEach(file -> checkpoints.add(parse(file)));
        checkpoints.sort(Comparator.comparing(SourceCheckpoint::sourceId)
                .thenComparing(SourceCheckpoint::connectorInstanceId));
        return List.copyOf(checkpoints);
    }

    private void move(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Some network filesystems refuse an atomic move. A plain replace is still better than
            // failing the run, and it is worth knowing that on such a volume a crash during the
            // move can leave no position file at all -- which re-reads the source, and duplicates.
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // The write already failed and is being reported; a leftover temporary file in the
            // checkpoint directory is not the thing to interrupt that with.
        }
    }

    private SourceCheckpoint parse(Path file) {
        Object parsed;
        try {
            LoaderOptions loaderOptions = new LoaderOptions();
            loaderOptions.setAllowDuplicateKeys(false);
            parsed = new Yaml(new SafeConstructor(loaderOptions))
                    .load(Files.newBufferedReader(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CheckpointStoreException("Cannot read the checkpoint " + file, e);
        } catch (RuntimeException e) {
            throw new CheckpointStoreException(
                    "The checkpoint " + file + " is not valid YAML: " + e.getMessage(), null);
        }
        if (!(parsed instanceof Map<?, ?> document)) {
            throw new CheckpointStoreException(
                    "The checkpoint " + file + " is not a mapping", null);
        }
        String sourceId = text(document, KEY_SOURCE_ID, file);
        String instanceId = text(document, KEY_INSTANCE_ID, file);
        String position = text(document, KEY_POSITION, file);
        String recordedAt = text(document, KEY_RECORDED_AT, file);
        try {
            return new SourceCheckpoint(sourceId, instanceId, position, Instant.parse(recordedAt));
        } catch (DateTimeParseException e) {
            throw new CheckpointStoreException("The checkpoint " + file + " has a '" + KEY_RECORDED_AT
                    + "' that is not an ISO-8601 instant: '" + recordedAt + "'", null);
        } catch (IllegalArgumentException e) {
            throw new CheckpointStoreException(
                    "The checkpoint " + file + " is unusable: " + e.getMessage(), null);
        }
    }

    private static String text(Map<?, ?> document, String key, Path file) {
        Object value = document.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new CheckpointStoreException(
                    "The checkpoint " + file + " is missing '" + key + "'", null);
        }
        return String.valueOf(value);
    }

    /**
     * The file a connector instance's position lives in.
     *
     * <p>A source id is free text, so it cannot be a filename as it stands. The readable part is
     * sanitised for a human reading the directory, and a hash of the exact pair is appended so two
     * ids that sanitise to the same text still get two files -- {@code riverton/cad} and
     * {@code riverton:cad} otherwise share one, and one connector reads the other's position.
     */
    Path fileFor(String sourceId, String connectorInstanceId) {
        String readable = sanitise(sourceId) + "__" + sanitise(connectorInstanceId);
        return directory.resolve(readable + "-" + shortHash(sourceId, connectorInstanceId) + EXTENSION);
    }

    private static String sanitise(String value) {
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() <= 60 ? cleaned : cleaned.substring(0, 60);
    }

    private static String shortHash(String sourceId, String connectorInstanceId) {
        // The separator is part of what is hashed, so ("a", "bc") and ("ab", "c") differ.
        byte[] key = (sourceId + " " + connectorInstanceId).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    @Override
    public String toString() {
        return "FileSourceCheckpointStore[" + directory + "]";
    }

    /** A checkpoint could not be read or written. Never swallowed: see the class javadoc. */
    public static final class CheckpointStoreException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        CheckpointStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
