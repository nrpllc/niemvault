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

    testImplementation(libs.flink.test.utils)
}
