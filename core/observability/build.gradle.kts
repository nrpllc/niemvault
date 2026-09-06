plugins {
    id("niem.java-conventions")
}

description = "Structured observability events (spec §4.7) and the emitters that deliver them."

dependencies {
    // Jackson only for the JSON line format of the logging sink. Nothing in the event model
    // itself depends on it, so an alternative sink is free to serialise differently.
    implementation(libs.jackson.databind)
}
