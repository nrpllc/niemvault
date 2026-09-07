plugins {
    id("niem.java-conventions")
}

description = "Replay driver (spec §5). Given a bronze range and a mapping version, rebuild silver and gold."

dependencies {
    api(project(":runtime:engine"))
    api(project(":storage"))
    api(project(":projections:api"))

    testImplementation(project(":identity:internal"))
    testImplementation(project(":projections:graph"))
    testImplementation(project(":connectors:file"))
    testImplementation(project(":modules:law-enforcement"))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.minio)
    testImplementation(libs.testcontainers.neo4j)
    testImplementation(libs.aws.s3)
    // The driver is deliberately not exposed by projections:graph -- the whole point is that no
    // backend type leaks past ProjectionWriter. This test reads the graph directly to verify the
    // replay, so it declares the dependency itself.
    testImplementation(libs.neo4j.driver)
}
