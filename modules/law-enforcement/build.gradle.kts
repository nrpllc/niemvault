plugins {
    id("niem.java-conventions")
}

description = "Law enforcement domain module: canonical extensions, mapping artifacts, contracts, and fixtures."

dependencies {
    // Content only for now -- mappings, contracts, and fixtures are resources, not code. The
    // module carries a dependency on the engine so its artifacts can be validated against the
    // loader that will consume them.
    testImplementation(project(":runtime:engine"))
    testImplementation(project(":identity:internal"))
}
