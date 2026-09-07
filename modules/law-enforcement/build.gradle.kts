plugins {
    id("niem.java-conventions")
}

description = "Law enforcement domain module: mapping artifacts, hop contracts, and fixtures."

dependencies {
    // Content only. The module ships mappings, contracts, and fixtures as resources; the engine
    // that consumes them is a test dependency so the artifacts are exercised where they live.
    testImplementation(project(":runtime:engine"))
    testImplementation(project(":connectors:file"))
    testImplementation(project(":identity:internal"))
    testImplementation(project(":storage"))
}
