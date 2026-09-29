plugins {
    id("niem.java-conventions")
}

description = "Elasticsearch search projection (ADR 0007, ADR 0036). What an investigator opens first."

dependencies {
    api(project(":projections:api"))

    implementation(libs.bundles.jackson)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.elasticsearch)
}
