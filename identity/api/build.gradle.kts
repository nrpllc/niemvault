plugins {
    id("niem.java-conventions")
}

description = "ResolutionProvider SPI and the platform's own cluster index (spec §4.5)."

dependencies {
    api(project(":core:observability"))
}
