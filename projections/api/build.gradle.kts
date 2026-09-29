plugins {
    id("niem.java-conventions")
}

description = "ProjectionWriter SPI (spec §4.6). Gold is polymorphic: one silver source, several shapes."

dependencies {
    api(project(":core:canonical"))
    api(project(":core:observability"))

    // A projection is named in a file (ADR 0035), read the way source and exchange definitions are.
    implementation(libs.snakeyaml)
}
