plugins {
    id("niem.java-conventions")
}

description = "SourceConnector SPI (spec §4.3). Transport differences must not leak past the landing boundary."

dependencies {
    // Connectors produce RawEnvelope; that type is the landing boundary and part of this API.
    api(project(":storage"))
    api(project(":core:observability"))

    implementation(libs.snakeyaml)
}
