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
}
