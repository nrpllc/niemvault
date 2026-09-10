plugins {
    id("niem.java-conventions")
}

description = "Kafka connector (spec §4.3, Phase 2). Bounded slices of a topic, landed like any other source."

dependencies {
    api(project(":connectors:api"))

    implementation(libs.kafka.clients)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.kafka)
}
