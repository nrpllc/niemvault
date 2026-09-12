package gov.niemplatform.connectors.pull;

import gov.niemplatform.connectors.api.SourceCheckpointStore;
import java.util.Locale;
import java.util.Optional;

/**
 * What a pull does about the files it has already landed (ADR 0031).
 *
 * <p>The central question of a pull transport, and the reason it is configuration with no default.
 * A remote directory does not remember that anyone read it, so something has to, and the four
 * answers differ in what they need from the remote server and in which failure they leave behind.
 *
 * <p>A push transport never faces this. A Kafka consumer group is a position the broker keeps, and
 * ADR 0028 could simply advance it. Here the operator has to say who remembers.
 */
public enum AfterDownload {

    /**
     * Move each landed file into an archive directory on the remote server.
     *
     * <p>The remote directory becomes the position: a file that is still there has not been landed.
     * The strongest of the four, because nothing outside the server has to stay in step with it --
     * a restored backup of this platform cannot disagree with the source about what was read.
     *
     * <p>Needs write access to the remote server, which an agency pulling from someone else's
     * system often does not have.
     */
    ARCHIVE(false),

    /**
     * Delete each landed file from the remote server.
     *
     * <p>Same position semantics as {@link #ARCHIVE} and the same requirement for write access, and
     * it destroys the source's copy. Right where the remote directory is a spool the source expects
     * to be drained; wrong wherever the source's copy is the system of record, because bronze is
     * then the only copy and a landing bug has nothing to re-read from.
     */
    DELETE(false),

    /**
     * Remember the last file landed, and pull only what sorts after it.
     *
     * <p>For a read-only pull. The position lives in this platform's
     * {@link SourceCheckpointStore} rather than on the remote server, which is what makes it work
     * without write access and also what makes it the weaker guarantee: the two can disagree.
     *
     * <p><strong>It can skip a late arrival.</strong> The watermark is (modification time, name), so
     * a file that appears after a run but is stamped older than the last one landed sorts behind the
     * watermark and is never pulled. Nothing reports it -- the record simply never arrives. That is
     * a real hazard on a server that preserves an original timestamp on upload, and the reason
     * {@link #ARCHIVE} is the better answer wherever write access exists.
     */
    WATERMARK(true),

    /**
     * Remember nothing and re-read the directory every run.
     *
     * <p>The posture the file-drop connector has always had, and it is not the careless option.
     * Envelope identity is derived from source, offset and content hash, so a re-landed file lands
     * with the identity it had the first time: the duplicates are exactly detectable rather than
     * merely likely, and nothing can be skipped. What it costs is bronze growth proportional to how
     * long files linger in the directory.
     *
     * <p>Right for a directory someone else prunes, and for a first run where an operator would
     * rather duplicate than risk a watermark that silently skips.
     */
    NONE(false);

    private final boolean needsCheckpointStore;

    AfterDownload(boolean needsCheckpointStore) {
        this.needsCheckpointStore = needsCheckpointStore;
    }

    /** Whether this posture keeps its position in the platform rather than on the remote server. */
    public boolean needsCheckpointStore() {
        return needsCheckpointStore;
    }

    /** Whether this posture writes to the remote server. */
    public boolean writesToTheRemoteServer() {
        return this == ARCHIVE || this == DELETE;
    }

    public static Optional<AfterDownload> parse(String declared) {
        return switch (declared.toLowerCase(Locale.ROOT)) {
            case "archive" -> Optional.of(ARCHIVE);
            case "delete" -> Optional.of(DELETE);
            case "watermark" -> Optional.of(WATERMARK);
            case "none" -> Optional.of(NONE);
            default -> Optional.empty();
        };
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
