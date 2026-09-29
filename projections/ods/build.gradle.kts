plugins {
    id("niem.java-conventions")
}

description = "PostgreSQL operational data store (ADR 0035). Canonical current state, queryable in SQL."

dependencies {
    api(project(":projections:api"))

    implementation(libs.postgresql)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
