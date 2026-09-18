plugins {
    id("niem.java-conventions")
}

description = "Exchange SPI (ADR 0034). Canonical records assembled into a document and submitted to an external system of record."

dependencies {
    // An exchange is declared against the canonical model and validated against it on load, so
    // the descriptors are part of this API rather than an implementation detail of it.
    api(project(":core:canonical"))

    implementation(libs.snakeyaml)
}
