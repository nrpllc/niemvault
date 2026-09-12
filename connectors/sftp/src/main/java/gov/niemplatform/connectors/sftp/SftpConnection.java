package gov.niemplatform.connectors.sftp;

import gov.niemplatform.connectors.pull.RemoteDirectory;
import gov.niemplatform.connectors.pull.RemoteFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

/**
 * One live SSH session and the SFTP channel on it.
 *
 * <p>Exists to keep the protocol in one place. The connector decides what to read and the handle
 * decides when to acknowledge; neither should be holding an {@code SshClient} open or deciding what
 * a failed {@code rename} means.
 *
 * <p>A connection is opened per read and closed with the handle. Holding one between runs would
 * mean a connector idle in a scheduled job was still an authenticated session on somebody else's
 * server, which is both a resource an agency is unlikely to want held and a session that will have
 * been dropped by an idle timeout before the next run anyway.
 */
final class SftpConnection implements RemoteDirectory {

    private final SshClient client;
    private final ClientSession session;
    private final SftpClient sftp;

    SftpConnection(SshClient client, ClientSession session, SftpClient sftp) {
        this.client = client;
        this.session = session;
        this.sftp = sftp;
    }

    /**
     * Regular files in a directory whose names match a glob.
     *
     * <p>Directories and anything that is not a regular file are skipped rather than reported: a
     * remote drop directory routinely holds an archive subdirectory, and a connector that failed on
     * it would fail on the very layout {@link AfterDownload#ARCHIVE} creates.
     *
     * <p>Sorted into {@link RemoteFile#READ_ORDER}, because the order files are read in is the order
     * they land in and a watermark is expressed in those terms.
     */
    @Override
    public List<RemoteFile> list(String directory, PathMatcher pattern) throws IOException {
        List<RemoteFile> files = new ArrayList<>();
        for (SftpClient.DirEntry entry : sftp.readDir(directory)) {
            String name = entry.getFilename();
            if (name.equals(".") || name.equals("..")) {
                continue;
            }
            SftpClient.Attributes attributes = entry.getAttributes();
            if (!attributes.isRegularFile()) {
                continue;
            }
            if (!pattern.matches(Path.of(name))) {
                continue;
            }
            files.add(new RemoteFile(
                    name,
                    attributes.getModifyTime().toInstant(),
                    attributes.getSize()));
        }
        files.sort(RemoteFile.READ_ORDER);
        return files;
    }

    /** Opens a file for reading. The caller closes it. */
    @Override
    public InputStream read(String directory, String name) throws IOException {
        return sftp.read(RemoteDirectory.join(directory, name));
    }

    /**
     * Moves a landed file into the archive directory.
     *
     * <p>On a name that is already taken the landing time is appended rather than overwriting or
     * failing. A source that exports {@code cad.csv} every night -- which is the common case, not an
     * edge one -- would otherwise either lose last night's archived copy or stop the run dead on the
     * second night.
     */
    @Override
    public void archive(String directory, String archiveDirectory, String name, long stampMillis)
            throws IOException {
        String from = RemoteDirectory.join(directory, name);
        String to = RemoteDirectory.join(archiveDirectory, name);
        if (exists(to)) {
            to = to + "." + stampMillis;
        }
        sftp.rename(from, to);
    }

    /** Deletes a landed file from the remote server. */
    @Override
    public void delete(String directory, String name) throws IOException {
        sftp.remove(RemoteDirectory.join(directory, name));
    }

    /** Whether a path exists, distinguishing "absent" from "could not be asked". */
    boolean exists(String path) throws IOException {
        try {
            sftp.stat(path);
            return true;
        } catch (org.apache.sshd.sftp.common.SftpException e) {
            if (e.getStatus() == org.apache.sshd.sftp.common.SftpConstants.SSH_FX_NO_SUCH_FILE
                    || e.getStatus() == org.apache.sshd.sftp.common.SftpConstants.SSH_FX_NO_SUCH_PATH) {
                return false;
            }
            throw e;
        }
    }

    /** Whether a path is a directory that can be listed. */
    @Override
    public boolean isDirectory(String path) throws IOException {
        try {
            return sftp.stat(path).isDirectory();
        } catch (org.apache.sshd.sftp.common.SftpException e) {
            return false;
        }
    }

    @Override
    public void close() {
        // Closed innermost first, and each failure swallowed so one stuck channel cannot leak the
        // client behind it. A connection is being torn down; there is nothing left to report to.
        closeQuietly(sftp);
        closeQuietly(session);
        closeQuietly(client);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // See close().
        }
    }

    /** How long a connect, authenticate or health probe waits before giving up. */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
}
