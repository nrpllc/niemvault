plugins {
    id("niem.java-conventions")
}

description = "SFTP connector (spec §4.3, ADR 0031). A remote directory pulled over SSH, landed like any other source."

dependencies {
    api(project(":connectors:pull"))

    implementation(libs.sshd.core)
    implementation(libs.sshd.sftp)

    // The tests run a real SFTP server on a loopback port rather than mocking the protocol. The
    // server half of sshd is the same artifact as the client half, so there is nothing extra to
    // declare -- only the test-scoped intent to use it as a server.
    testImplementation(libs.sshd.core)
    testImplementation(libs.sshd.sftp)
}
