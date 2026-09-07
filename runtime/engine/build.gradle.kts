plugins {
    id("niem.java-conventions")
}

description = "Mapping definitions compiled into a Flink job graph (spec §5). One definition, two modalities."

dependencies {
    api(project(":runtime:transforms"))
    api(project(":storage"))
    api(project(":identity:api"))
    api(project(":connectors:api"))

    implementation(libs.snakeyaml)

    // Flink is an implementation detail on purpose. The mapping pipeline is pure Java; only
    // FlinkMappingJob touches Flink types. That is what makes acceptance criterion 7 provable
    // rather than asserted -- batch and streaming invoke the same pipeline object.
    implementation(libs.bundles.flink.embedded)
    // Serves the job-graph dashboard when a run asks for it. Present on the classpath so an
    // embedded run can show its own DAG; Flink activates it only when the REST port is bound.
    implementation(libs.flink.runtime.web)

    testImplementation(libs.flink.test.utils)

    // The bundled resolver is a test dependency, not a runtime one: the engine talks to the
    // ResolutionProvider SPI and must not know which implementation a deployment installed.
    testImplementation(project(":identity:internal"))

    // The law enforcement module owns the shipped mapping artifact. Reading it from the
    // classpath rather than keeping a second copy here is what stops the two drifting -- a
    // duplicated mapping that quietly diverges from the one that ships is worse than no test.
    testImplementation(project(":modules:law-enforcement"))
}
