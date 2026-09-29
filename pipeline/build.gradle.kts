plugins {
    id("niem.java-conventions")
}

description = "A pipeline as a versioned artifact (ADR 0037): an origin, the mapping, and where " +
    "the records go -- resolved by name against a module and a deployment's own definitions."

dependencies {
    api(project(":connectors:api"))
    api(project(":projections:api"))
    api(project(":exchange:api"))
    api(project(":runtime:engine"))
    api(project(":core:canonical"))

    implementation(libs.snakeyaml)
}
