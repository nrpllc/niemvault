plugins {
    id("niem.java-conventions")
}

description = "FTPS connector (spec §4.3, ADR 0033). A remote directory pulled over FTP with TLS, landed like any other source."

dependencies {
    api(project(":connectors:pull"))

    implementation(libs.commons.net)

    // A real FTP server on a loopback port. Mocking the protocol would only confirm that the mock
    // behaves as the test expects, and the interesting failures are all in the server.
    testImplementation(libs.ftpserver.core)
}
