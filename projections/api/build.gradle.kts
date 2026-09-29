plugins {
    id("niem.java-conventions")
}

description = "ProjectionWriter SPI (spec §4.6). Gold is polymorphic: one silver source, several shapes."

dependencies {
    // Each implementation describes its own settings, so an authoring surface can build a form
    // from what the component actually reads rather than from a copy that drifts (ADR 0037).
    api(project(":core:settings"))
    api(project(":core:canonical"))
    api(project(":core:observability"))

    // A projection is named in a file (ADR 0035), read the way source and exchange definitions are.
    implementation(libs.snakeyaml)
}
