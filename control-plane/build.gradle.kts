plugins {
    id("niem.java-conventions")
}

description = "Mapping authoring and catalogue surface (spec §4.8, ADR 0021). JVM, in-process with the validators."

dependencies {
    // The point of ADR 0021: the authoring surface calls the validators directly rather than
    // carrying its own. Two validators for one artifact format would drift, and the editor would
    // start accepting mappings the runtime rejects.
    api(project(":runtime:engine"))
    api(project(":core:content"))
    api(project(":core:contracts"))
    // The advisor reads redacted value shapes, never values (ADR 0023).
    api(project(":core:observability"))
    implementation(project(":runtime:transforms"))
    // The catalogue reports how a source arrives, which is the connector's own declaration
    // (ADR 0027). The SPI only -- which transports a deployment actually carries is discovered
    // at runtime, so the surface never depends on a particular one.
    api(project(":connectors:api"))
    // Value shapes are profiled from what has already landed in bronze (ADR 0023).
    implementation(project(":storage"))

    // The pipeline designer (ADR 0037): the same resolver `niem run --pipeline` uses, so the designer
    // and the command line cannot disagree about whether a pipeline runs.
    implementation(project(":pipeline"))
    // Preview maps sample records in memory with a fresh cluster index, as a direct run would.
    implementation(project(":identity:internal"))

    implementation(libs.bundles.jackson)
    implementation(libs.snakeyaml)

    // The tests drive the surface against the artifacts the law enforcement module actually
    // ships, so a broken mapping or contract fails here too.
    testImplementation(project(":modules:law-enforcement"))
    // The palette is discovered, so its tests need the implementations a deployment ships.
    testRuntimeOnly(project(":connectors:file"))
    testRuntimeOnly(project(":connectors:kafka"))
    testRuntimeOnly(project(":projections:ods"))
    testRuntimeOnly(project(":projections:search"))
    testRuntimeOnly(project(":projections:graph"))
    testRuntimeOnly(project(":exchange:cch"))
}
