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
    // Silver ships as Iceberg (ADR 0005). Implementation, not api: nothing above
    // storage.api may see an Iceberg type, which is what keeps the backend swappable.
    implementation(libs.iceberg.core)
    implementation(libs.iceberg.api)
    implementation(libs.iceberg.data)
    implementation(libs.iceberg.parquet)
    implementation(libs.iceberg.aws)
    implementation(libs.aws.s3)
    implementation(libs.aws.url.connection.client)
    // Iceberg's AwsProperties touches STS model classes during construction even when no role
    // is assumed, so this is required for S3FileIO to initialise at all.
    implementation(libs.aws.sts)
    // Embedded catalog for a single-node deployment. A cluster deployment points the same
    // catalog at its own database.
    implementation(libs.h2)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.minio)
}
