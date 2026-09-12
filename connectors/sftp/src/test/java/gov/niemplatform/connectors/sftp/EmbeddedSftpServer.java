package gov.niemplatform.connectors.sftp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

/**
 * A real SFTP server on a loopback port, serving a temporary directory.
 *
 * <p>The connector's job is to speak a protocol correctly, and a test double for that protocol
 * would only ever confirm that the double behaves as the test expects. The interesting failures --
 * a rename onto a name that exists, a directory entry that is not a regular file, a modification
 * time that arrives in seconds rather than millis -- live in the server, so the server is real.
 *
 * <p>In-process rather than a container, so the everyday test loop needs no Docker. The one thing
 * lost is a server that behaves differently from this one, which is what the {@code accept-any}
 * host key path and the archive-collision handling exist to absorb.
 */
final class EmbeddedSftpServer implements AutoCloseable {

    static final String USERNAME = "niem";
    static final String PASSWORD = "correct-horse";

    private final SshServer server;
    private final Path root;

    private EmbeddedSftpServer(SshServer server, Path root) {
        this.server = server;
        this.root = root;
    }

    static EmbeddedSftpServer serving(Path root) throws IOException {
        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        server.setPasswordAuthenticator(
                (username, password, session) -> USERNAME.equals(username) && PASSWORD.equals(password));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(root));
        server.start();
        return new EmbeddedSftpServer(server, root);
    }

    int port() {
        return server.getPort();
    }

    Path root() {
        return root;
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
    public void close() throws IOException {
        server.stop(true);
        server.close();
    }
}
