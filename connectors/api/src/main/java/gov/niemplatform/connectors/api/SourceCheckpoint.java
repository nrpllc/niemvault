package gov.niemplatform.connectors.api;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * How far a connector has read, when the source does not remember for it.
 *
 * <p>Kafka never needed this. A consumer group <em>is</em> the read position, held on the broker,
 * and {@link SourceHandle#acknowledge()} advances it with a commit. An SFTP directory holds no
 * position at all, and a database's change log holds one the reader must carry itself. For those
 * transports the platform has to remember on the source's behalf, and a position remembered only
 * in memory is one that resets to the beginning of time on the next restart -- which for a nightly
 * pull means re-downloading and re-landing the whole directory, and for a change feed means
 * replaying the log from its start.
 *
 * <p><strong>The position is opaque.</strong> Same reasoning as {@link ConnectorType} being a value
 * type and settings being untyped text: a transport this platform has never seen must be able to
 * checkpoint without changing this class. An SFTP connector writes a filename watermark here, a CDC
 * connector a log sequence number, and neither shape means anything to the platform. Only the
 * connector that wrote it ever interprets it.
 *
 * <p>Ordering is not implied. This is a bookmark, not an offset: {@link gov.niemplatform.storage.api.SourceOffset}
 * is the ordered thing, and it lives on the envelope where bronze can range over it.
 *
 * @param sourceId the configured source this position belongs to
 * @param connectorInstanceId which instance read that far, because two instances of one transport
 *     read independently and sharing a position would have each skip what the other landed
 * @param position the connector's own encoding of where it stopped
 * @param recordedAt when the position was written, which is after the bronze commit it covers
 */
public record SourceCheckpoint(
        String sourceId,
        String connectorInstanceId,
        String position,
        Instant recordedAt) implements Serializable {

    private static final long serialVersionUID = 1L;

    public SourceCheckpoint {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(connectorInstanceId, "connectorInstanceId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (sourceId.isBlank() || connectorInstanceId.isBlank()) {
            throw new IllegalArgumentException("A checkpoint needs a source id and an instance id");
        }
        if (position.isBlank()) {
            throw new IllegalArgumentException(
                    "A checkpoint position must not be blank. A connector with nothing to remember "
                            + "writes no checkpoint rather than an empty one: absent means 'never "
                            + "read', and blank would be indistinguishable from it");
        }
    }

    public static SourceCheckpoint of(
            String sourceId, String connectorInstanceId, String position, Instant recordedAt) {
        return new SourceCheckpoint(sourceId, connectorInstanceId, position, recordedAt);
    }

    /** Whether this checkpoint belongs to a given connector instance. */
    public boolean belongsTo(String otherSourceId, String otherInstanceId) {
        return sourceId.equals(otherSourceId) && connectorInstanceId.equals(otherInstanceId);
    }
}
