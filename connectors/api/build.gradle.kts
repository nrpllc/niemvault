plugins {
    id("niem.java-conventions")
}

description = "SourceConnector SPI (spec §4.3). Transport differences must not leak past the landing boundary."

dependencies {
    // Each implementation describes its own settings, so an authoring surface can build a form
    // from what the component actually reads rather than from a copy that drifts (ADR 0037).
    api(project(":core:settings"))
    // Connectors produce RawEnvelope; that type is the landing boundary and part of this API.
    api(project(":storage"))
    api(project(":core:observability"))

    implementation(libs.snakeyaml)
}
