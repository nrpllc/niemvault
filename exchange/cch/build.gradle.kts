plugins {
    id("niem.java-conventions")
}

description = "Criminal history exchange over HTTP (ADR 0034). Carries whatever the exchange definition assembles."

dependencies {
    api(project(":exchange:api"))

    implementation(libs.jackson.databind)
}
