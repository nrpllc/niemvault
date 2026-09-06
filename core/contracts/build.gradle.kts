plugins {
    id("niem.java-conventions")
}

description = "Hop contracts and their validator (spec §4.2). Contracts are on-disk artifacts, not code."

dependencies {
    // Schemas are derived from canonical type descriptors, and violations are reported as
    // observability failures. Both are part of this module's public surface, so both are api.
    api(project(":core:canonical"))
    api(project(":core:observability"))

    implementation(libs.snakeyaml)
}
