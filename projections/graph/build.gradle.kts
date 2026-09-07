plugins {
    id("niem.java-conventions")
}

description = "Neo4j graph projection (spec §4.6). NIEM associations are already edges; this honours that."

dependencies {
    api(project(":projections:api"))

    implementation(libs.neo4j.driver)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.neo4j)
}
