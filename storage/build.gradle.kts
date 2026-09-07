plugins {
    id("niem.java-conventions")
}

description = "Bronze landing and canonical silver storage (spec §4.4). Interfaces first, backends behind them."

dependencies {
    api(project(":core:canonical"))
    api(project(":core:contracts"))

    implementation(libs.parquet.avro)
    implementation(libs.avro)
    // Parquet's builder API takes a Hadoop Configuration even when the sink is a pure-NIO
    // OutputFile. Present at compile time; no Hadoop FileSystem is touched at runtime, which is
    // what keeps bronze working on a developer machine without winutils.exe. See ADR 0005.
    implementation(libs.hadoop.common) {
        exclude(group = "org.slf4j")
    }
    implementation(libs.hadoop.mapreduce.core) {
        exclude(group = "org.slf4j")
    }
    implementation(libs.bundles.jackson)

    // ADR 0005: whether Iceberg can run embedded on a developer machine decides the silver
    // table format. Spiked before it is designed around.
    testImplementation(libs.iceberg.core)
    testImplementation(libs.iceberg.api)
    testImplementation(libs.iceberg.data)
    testImplementation(libs.iceberg.parquet)
    testImplementation(libs.iceberg.aws)
    testImplementation(libs.aws.s3)
    testImplementation(libs.aws.url.connection.client)
    testImplementation(libs.aws.sts)
    testImplementation(libs.h2)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.minio)
}
