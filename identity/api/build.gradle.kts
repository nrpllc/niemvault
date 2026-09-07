plugins {
    id("niem.java-conventions")
}

description = "ResolutionProvider SPI and the platform's own cluster index (spec §4.5)."

dependencies {
    api(project(":core:observability"))
    // A cluster identifier is tenant-scoped (ADR 0025), and TenantId lives with the canonical model.
    api(project(":core:canonical"))
}
