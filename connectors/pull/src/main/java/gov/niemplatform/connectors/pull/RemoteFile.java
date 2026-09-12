package gov.niemplatform.connectors.pull;

import java.time.Instant;
import java.util.Comparator;

/**
 * One file seen in the remote directory.
 *
 * @param name the file name, which is also what an offset and an archive move are expressed in
 * @param modifiedAt what the server says about when it was last written, used both as the
 *     source-asserted timestamp and as the high half of a watermark
 * @param size in bytes, for reporting only
 */
public record RemoteFile(String name, Instant modifiedAt, long size) {

    /**
     * Modification time first, then name.
     *
     * <p>The order files are read in, and therefore the order they land in. It has to be a total
     * order and the same one every run, because {@link AfterDownload#WATERMARK} encodes a position
     * in exactly these terms: a run that read in a different order could leave a watermark ahead of
     * a file it never read.
     *
     * <p>Time before name, rather than name alone, because a source that names its exports by
     * content rather than by date has no useful name order -- and time is the thing a watermark
     * actually means.
     */
    public static final Comparator<RemoteFile> READ_ORDER =
            Comparator.comparing(RemoteFile::modifiedAt).thenComparing(RemoteFile::name);

    /**
     * This file's position, ordered lexicographically to match {@link #READ_ORDER}.
     *
     * <p>Zero-padded to nineteen digits, the width of {@link Long#MAX_VALUE}, for the reason the
     * Kafka connector pads an offset: unpadded, a file from epoch-millis 9 would sort after one
     * from 10 and a comparison against the stored watermark would silently include or exclude the
     * wrong files.
     */
    public String watermark() {
        return "%019d#%s".formatted(modifiedAt.toEpochMilli(), name);
    }

    /** Whether this file sorts after a stored watermark, and so has not been landed yet. */
    public boolean isAfter(String storedWatermark) {
        return watermark().compareTo(storedWatermark) > 0;
    }
}
