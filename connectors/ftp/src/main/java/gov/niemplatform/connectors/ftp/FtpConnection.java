package gov.niemplatform.connectors.ftp;

import gov.niemplatform.connectors.pull.RemoteDirectory;
import gov.niemplatform.connectors.pull.RemoteFile;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

/**
 * One authenticated FTP or FTPS session, presented as a remote directory.
 *
 * <p>The protocol differences that matter to a pull all live here; everything above this is the
 * shared {@code connectors:pull} machinery, which is the point of the split. Two of them are worth
 * naming, because both are FTP behaving unlike SFTP in ways that corrupt data rather than fail:
 *
 * <ul>
 *   <li><strong>Transfer mode.</strong> FTP defaults to ASCII, which rewrites line endings in
 *       flight. A CSV pulled in ASCII mode from a Windows server arrives with its bytes altered, and
 *       bronze then holds something the source never sent -- which breaks the content hash that
 *       envelope identity is built on. Binary is set explicitly and is not configurable.
 *   <li><strong>Completion is a separate reply.</strong> A data connection can close cleanly on a
 *       transfer the server then reports as failed. Reading to end-of-stream is therefore not
 *       evidence that a file arrived whole, and a truncated file that looked complete would be
 *       landed as though it were. Every read is followed by {@code completePendingCommand()}.
 * </ul>
 */
final class FtpConnection implements RemoteDirectory {

    private final FTPClient client;

    FtpConnection(FTPClient client) {
        this.client = client;
    }

    @Override
    public List<RemoteFile> list(String directory, PathMatcher pattern) throws IOException {
        FTPFile[] entries = client.listFiles(directory);
        requireReply("listing " + directory);
        List<RemoteFile> files = new ArrayList<>();
        for (FTPFile entry : entries) {
            if (entry == null || !entry.isFile()) {
                continue;
            }
            String name = entry.getName();
            if (name.equals(".") || name.equals("..")) {
                continue;
            }
            if (!pattern.matches(Path.of(name))) {
                continue;
            }
            files.add(new RemoteFile(name, modifiedAt(entry), entry.getSize()));
        }
        files.sort(RemoteFile.READ_ORDER);
        return files;
    }

    /**
     * When the server says the file was last written.
     *
     * <p>FTP's {@code LIST} output is a directory listing meant for a human terminal, and its
     * timestamp is famously lossy: many servers report minute precision, and for files older than
     * six months some report only a year. Where {@code MDTM} is supported it is asked instead,
     * because a pull ordered by modification time is only as good as the timestamps it sorts on --
     * and a watermark built on a truncated one can skip a file.
     */
    private Instant modifiedAt(FTPFile entry) throws IOException {
        if (entry.getTimestampInstant() != null) {
            return entry.getTimestampInstant();
        }
        // No timestamp at all. Epoch rather than "now": now would sort an undated file ahead of
        // everything and park the watermark past files that have not been read.
        return Instant.EPOCH;
    }

    @Override
    public InputStream read(String directory, String name) throws IOException {
        InputStream stream = client.retrieveFileStream(RemoteDirectory.join(directory, name));
        if (stream == null) {
            throw new IOException("Cannot read " + RemoteDirectory.join(directory, name)
                    + ": " + client.getReplyString().trim());
        }
        // The close that completes the command. Without it the next request on this session reads
        // the previous transfer's pending reply and every call afterwards is answering the wrong
        // question -- and a failed transfer is never noticed at all.
        return new FilterInputStream(stream) {
            @Override
            public void close() throws IOException {
                super.close();
                if (!client.completePendingCommand()) {
                    throw new IOException("The server reported that transferring "
                            + RemoteDirectory.join(directory, name) + " did not complete: "
                            + client.getReplyString().trim()
                            + ". The file has not been landed and will be read again.");
                }
            }
        };
    }

    @Override
    public void archive(String directory, String archiveDirectory, String name, long stampMillis)
            throws IOException {
        String from = RemoteDirectory.join(directory, name);
        String to = RemoteDirectory.join(archiveDirectory, name);
        if (exists(to)) {
            to = to + "." + stampMillis;
        }
        if (!client.rename(from, to)) {
            throw new IOException("Cannot archive " + from + " to " + to + ": "
                    + client.getReplyString().trim());
        }
    }

    @Override
    public void delete(String directory, String name) throws IOException {
        String path = RemoteDirectory.join(directory, name);
        if (!client.deleteFile(path)) {
            throw new IOException("Cannot delete " + path + ": " + client.getReplyString().trim());
        }
    }

    private boolean exists(String path) throws IOException {
        // getSize returns null for an absent path on servers that support SIZE; where it is not
        // supported the listing is the fallback and an absent file lists as nothing.
        FTPFile[] found = client.listFiles(path);
        return found != null && found.length > 0;
    }

    @Override
    public boolean isDirectory(String path) throws IOException {
        String previous = client.printWorkingDirectory();
        if (!client.changeWorkingDirectory(path)) {
            return false;
        }
        if (previous != null) {
            client.changeWorkingDirectory(previous);
        }
        return true;
    }

    private void requireReply(String what) throws IOException {
        if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
            throw new IOException("The server refused " + what + ": " + client.getReplyString().trim());
        }
    }

    @Override
    public void close() {
        try {
            if (client.isConnected()) {
                client.logout();
            }
        } catch (IOException ignored) {
            // Being torn down; a refused logout is not worth reporting over whatever caused it.
        }
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
        } catch (IOException ignored) {
            // See above.
        }
    }
}
