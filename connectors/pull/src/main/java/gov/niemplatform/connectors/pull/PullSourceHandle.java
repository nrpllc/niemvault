package gov.niemplatform.connectors.pull;

import gov.niemplatform.connectors.api.SourceCheckpoint;
import gov.niemplatform.connectors.api.SourceCheckpointStore;
import gov.niemplatform.connectors.api.SourceHandle;
import gov.niemplatform.storage.api.RawEnvelope;
import gov.niemplatform.storage.api.SourceOffset;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * One pull of a remote directory, in whatever protocol reaches it (ADR 0031, ADR 0033).
 *
 * <p>Bounded, in the sense ADR 0028 established for Kafka: a list of files taken when the handle was
 * opened, read to the end, and finished. Files that appear during the read are next run's problem,
 * which is what makes a scheduled pull a continuous feed rather than a job that never returns.
 *
 * <h2>Only a file read to its end counts as landed</h2>
 *
 * <p>The subtle half, and the same one ADR 0028 found in Kafka. {@link #acknowledge()} is called
 * after each bronze batch, and a batch boundary falls wherever it falls -- usually in the middle of
 * a file. Archiving, deleting or watermarking a file whose remaining lines are still unread would
 * discard records bronze has never seen, and no later run would look for them again: the file is
 * gone from the directory, or sorts behind the watermark.
 *
 * <p>So a file joins the acknowledgeable set only when its last record has been yielded, and a run
 * that dies mid-file leaves that file exactly where it was. The next run re-reads it from the start
 * and re-lands the records the dead run had already committed -- duplicates, which bronze detects
 * because envelope identity is derived from source, offset and content hash.
 */
public final class PullSourceHandle implements SourceHandle {

    private final RemoteDirectory connection;
    private final List<RemoteFile> files;
    private final String directory;
    private final String archiveDirectory;
    private final AfterDownload afterDownload;
    private final SourceCheckpointStore checkpoints;
    private final RecordMode recordMode;
    private final int skipHeaderLines;
    private final Charset charset;
    private final String sourceId;
    private final String connectorInstanceId;
    private final Clock clock;

    /** Files whose last record has been yielded, in the order they were read. */
    private final List<RemoteFile> drained = new ArrayList<>();

    /** How much of {@link #drained} a previous acknowledgement has already dealt with. */
    private int acknowledgedThrough;

    private Records records;

    public PullSourceHandle(
            RemoteDirectory connection,
            List<RemoteFile> files,
            String directory,
            String archiveDirectory,
            AfterDownload afterDownload,
            SourceCheckpointStore checkpoints,
            RecordMode recordMode,
            int skipHeaderLines,
            Charset charset,
            String sourceId,
            String connectorInstanceId,
            Clock clock) {
        this.connection = connection;
        this.files = List.copyOf(files);
        this.directory = directory;
        this.archiveDirectory = archiveDirectory;
        this.afterDownload = afterDownload;
        this.checkpoints = checkpoints;
        this.recordMode = recordMode;
        this.skipHeaderLines = skipHeaderLines;
        this.charset = charset;
        this.sourceId = sourceId;
        this.connectorInstanceId = connectorInstanceId;
        this.clock = clock;
    }

    /** The files this pull will read, in read order. For reporting and for tests. */
    public List<RemoteFile> files() {
        return files;
    }

    @Override
    public Stream<RawEnvelope> envelopes() {
        records = new Records();
        return StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(records, Spliterator.ORDERED), false)
                .onClose(records::close);
    }

    /**
     * Deals with every file drained since the last call, and never with one still being read.
     *
     * <p>Called by {@code LandingService} only after a bronze batch has committed, which is what
     * makes archiving or deleting a remote file safe to do here and unsafe to do anywhere else.
     */
    @Override
    public void acknowledge() {
        List<RemoteFile> newlyDrained = drained.subList(acknowledgedThrough, drained.size());
        if (newlyDrained.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        try {
            for (RemoteFile file : newlyDrained) {
                switch (afterDownload) {
                    case ARCHIVE -> connection.archive(
                            directory, archiveDirectory, file.name(), now.toEpochMilli());
                    case DELETE -> connection.delete(directory, file.name());
                    case WATERMARK, NONE -> {
                        // Nothing on the remote server. WATERMARK records its position below, once
                        // per acknowledgement rather than once per file: it is a single value, and
                        // the last file drained is the only one it can be.
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Landed records could not be acknowledged on the remote server. They are in "
                            + "bronze; the files they came from are still in " + directory
                            + ", so the next run will land them again and bronze will show the "
                            + "duplicates", e);
        }
        if (afterDownload == AfterDownload.WATERMARK) {
            RemoteFile furthest = newlyDrained.get(newlyDrained.size() - 1);
            checkpoints.write(SourceCheckpoint.of(
                    sourceId, connectorInstanceId, furthest.watermark(), now));
        }
        acknowledgedThrough = drained.size();
    }

    @Override
    public void close() {
        if (records != null) {
            records.close();
        }
        connection.close();
    }

    /**
     * Walks the files, and the records within each.
     *
     * <p>An explicit iterator rather than a {@code flatMap} over per-file streams, because the one
     * thing this class has to get right is knowing when a file has been read to its end. {@code
     * flatMap} closes an inner stream when it is exhausted <em>and</em> when the outer stream is
     * abandoned, and those two cases must not be confused here: the second one means a file was only
     * partly read, and treating it as drained would archive away unlanded records.
     */
    private final class Records implements Iterator<RawEnvelope> {

        private int nextFileIndex;
        private RemoteFile current;
        private InputStream stream;
        private BufferedReader reader;
        private int lineNumber;
        private Instant ingestedAt;
        private boolean fileEmitted;
        private RawEnvelope pending;
        private boolean prefetched;
        private boolean closed;

        @Override
        public boolean hasNext() {
            prefetch();
            return pending != null;
        }

        /**
         * Hands over a record, and reads one past it.
         *
         * <p>The look-ahead is what makes "a file is drained when its last record has been handed
         * over" true, rather than "...when the consumer asks for one more and is told no". Those
         * differ only for a caller that stops at the last record without asking again -- and the
         * difference is a file that was fully landed and never archived, so the next run lands it
         * a second time. Making the guarantee depend on the shape of the caller's loop is the kind
         * of thing that holds until someone writes a different loop.
         */
        @Override
        public RawEnvelope next() {
            prefetch();
            if (pending == null) {
                throw new NoSuchElementException();
            }
            RawEnvelope envelope = pending;
            pending = null;
            prefetched = false;
            prefetch();
            return envelope;
        }

        private void prefetch() {
            if (prefetched || closed) {
                return;
            }
            prefetched = true;
            pending = advance();
        }

        private RawEnvelope advance() {
            while (true) {
                if (current == null && !openNextFile()) {
                    return null;
                }
                RawEnvelope envelope = readOne();
                if (envelope != null) {
                    return envelope;
                }
                // The file yielded its last record on the previous call, so it is now read to its
                // end. This is the only place a file becomes acknowledgeable.
                closeCurrent();
                drained.add(current);
                current = null;
            }
        }

        private boolean openNextFile() {
            if (nextFileIndex >= files.size()) {
                return false;
            }
            current = files.get(nextFileIndex++);
            lineNumber = 0;
            fileEmitted = false;
            ingestedAt = clock.instant();
            try {
                stream = connection.read(directory, current.name());
                reader = recordMode == RecordMode.LINE
                        ? new BufferedReader(new InputStreamReader(stream, charset))
                        : null;
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Cannot read " + RemoteDirectory.join(directory, current.name()), e);
            }
            return true;
        }

        private RawEnvelope readOne() {
            try {
                return recordMode == RecordMode.LINE ? readLine() : readWholeFile();
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Cannot read " + RemoteDirectory.join(directory, current.name()), e);
            }
        }

        private RawEnvelope readLine() throws IOException {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber <= skipHeaderLines || line.isBlank()) {
                    continue;
                }
                return new RawEnvelope(
                        sourceId,
                        connectorInstanceId,
                        ingestedAt,
                        current.modifiedAt(),
                        line.getBytes(charset),
                        // The same shape the file-drop connector uses, deliberately. A source that
                        // moves from a nightly drop to an SFTP pull keeps comparable offsets, so a
                        // bronze range spanning the move still means one thing.
                        SourceOffset.of("%s#%06d".formatted(current.name(), lineNumber)));
            }
            return null;
        }

        private RawEnvelope readWholeFile() throws IOException {
            if (fileEmitted) {
                return null;
            }
            fileEmitted = true;
            return new RawEnvelope(
                    sourceId,
                    connectorInstanceId,
                    ingestedAt,
                    current.modifiedAt(),
                    stream.readAllBytes(),
                    SourceOffset.of(current.name()));
        }

        private void closeCurrent() {
            try {
                if (reader != null) {
                    reader.close();
                } else if (stream != null) {
                    stream.close();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Cannot close " + RemoteDirectory.join(directory, current.name()), e);
            } finally {
                reader = null;
                stream = null;
            }
        }

        private void close() {
            if (closed) {
                return;
            }
            closed = true;
            pending = null;
            if (current == null) {
                return;
            }
            // Deliberately not added to drained: this file was abandoned part-read.
            try {
                closeCurrent();
            } catch (UncheckedIOException ignored) {
                // Already unwinding; a failure to close a stream is not what to report.
            }
            current = null;
        }
    }
}
