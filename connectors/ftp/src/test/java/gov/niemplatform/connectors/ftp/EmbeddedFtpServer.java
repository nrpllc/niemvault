package gov.niemplatform.connectors.ftp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;

/**
 * A real FTP server on a loopback port, serving a temporary directory.
 *
 * <p>The same reasoning as the SFTP connector's embedded server: the connector's job is to speak a
 * protocol correctly, and a double for that protocol would only ever confirm that the double
 * behaves as the test expects. FTP's interesting failures are all on the server side -- a listing
 * whose timestamp has no year, a rename onto a name that exists, a transfer whose completion is a
 * separate reply from the data connection closing.
 *
 * <p>Plain FTP rather than FTPS here. TLS is the part commons-net and the JDK implement between
 * them; what these tests exercise is the pull semantics over a real FTP conversation, and adding a
 * self-signed certificate to the loopback would test the JDK's trust store rather than this
 * connector. The TLS paths are covered by configuration tests instead.
 */
final class EmbeddedFtpServer implements AutoCloseable {

    static final String USERNAME = "niem";
    static final String PASSWORD = "correct-horse";

    private final FtpServer server;
    private final Path root;
    private final int port;

    private EmbeddedFtpServer(FtpServer server, Path root, int port) {
        this.server = server;
        this.root = root;
        this.port = port;
    }

    static EmbeddedFtpServer serving(Path root) throws IOException, FtpException {
        int freePort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            freePort = probe.getLocalPort();
        }

        FtpServerFactory factory = new FtpServerFactory();
        ListenerFactory listener = new ListenerFactory();
        listener.setServerAddress("127.0.0.1");
        listener.setPort(freePort);
        factory.addListener("default", listener.createListener());

        BaseUser user = new BaseUser();
        user.setName(USERNAME);
        user.setPassword(PASSWORD);
        user.setHomeDirectory(root.toAbsolutePath().toString());
        // Writable: archive and delete are two of the four afterDownload postures, and a read-only
        // server would make those tests pass for the wrong reason.
        user.setAuthorities(List.<Authority>of(new WritePermission()));
        factory.getUserManager().save(user);

        FtpServer server = factory.createServer();
        server.start();
        return new EmbeddedFtpServer(server, root, freePort);
    }

    int port() {
        return port;
    }

    /** Writes a file into the served directory with a chosen modification time. */
    Path give(String relativePath, String content, Instant modifiedAt) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.from(modifiedAt));
        return file;
    }

    @Override
    public void close() {
        server.stop();
    }
}
