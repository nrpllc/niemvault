package gov.niemplatform.connectors.pull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.PathMatcher;
import java.util.List;

/**
 * A directory on a remote server that a pull transport reads (ADR 0031, ADR 0033).
 *
 * <p>The whole of what {@link PullSourceHandle} needs from a protocol, and deliberately nothing
 * more. SFTP and FTPS differ in how a session is established, how the server is authenticated, and
 * what a failure looks like — and in none of the things below. List, read, and either move or
 * delete what has been landed: that is a pull, in any protocol anyone has shipped.
 *
 * <p>Keeping it this small is what makes the extraction worth doing. The rules that must not vary
 * between transports — that a file counts as landed only when its last record has been yielded,
 * that a watermark advances only after the bronze commit — live above this interface and are
 * written once. A second connector that reimplemented them would eventually disagree with the
 * first, and the disagreement would show up as records missing from bronze.
 */
public interface RemoteDirectory extends AutoCloseable {

    /**
     * Regular files in a directory whose names match a glob, in {@link RemoteFile#READ_ORDER}.
     *
     * <p>Directories and anything that is not a regular file are skipped rather than reported: a
     * remote drop directory routinely holds an archive subdirectory, and a transport that failed on
     * it would fail on the very layout {@link AfterDownload#ARCHIVE} creates.
     */
    List<RemoteFile> list(String directory, PathMatcher pattern) throws IOException;

    /** Opens a file for reading. The caller closes it. */
    InputStream read(String directory, String name) throws IOException;

    /**
     * Moves a landed file into the archive directory.
     *
     * <p>Implementations must not overwrite a name that is taken. A source exporting {@code cad.csv}
     * every night is the common case, and overwriting would destroy the previous archived copy;
     * {@code stampMillis} is supplied so a colliding name can be suffixed rather than lost.
     */
    void archive(String directory, String archiveDirectory, String name, long stampMillis)
            throws IOException;

    /** Deletes a landed file from the remote server. */
    void delete(String directory, String name) throws IOException;

    /** Whether a path is a directory that can be listed. */
    boolean isDirectory(String path) throws IOException;

    @Override
    void close();

    /** Joins a remote directory and a name. Remote paths are POSIX whatever this platform is. */
    static String join(String directory, String name) {
        if (directory.isEmpty() || directory.equals(".")) {
            return name;
        }
        return directory.endsWith("/") ? directory + name : directory + "/" + name;
    }
}
