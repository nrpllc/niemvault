plugins {
    id("niem.java-conventions")
    application
}

description = "Operator CLI (spec §8): validate, describe, author, run, replay, inspect."

dependencies {
    implementation(project(":runtime:engine"))
    implementation(project(":connectors:file"))
    // Every connector the platform ships is on the CLI's classpath so ConnectorRegistry can
    // discover it. A source names its transport in a file; the registry resolves it (§4.3).
    implementation(project(":connectors:kafka"))
    implementation(project(":connectors:sftp"))
    implementation(project(":connectors:ftp"))
    implementation(project(":identity:internal"))
    implementation(project(":storage"))
    // `replay` rebuilds silver and, when asked, the graph -- so the CLI carries both.
    implementation(project(":runtime:replay"))
    implementation(project(":projections:graph"))
    implementation(project(":core:content"))
    implementation(project(":control-plane"))

    // `simulate` produces a feed, so the CLI carries a Kafka producer as well as the consumer
    // the connector uses. The domain module supplies the records; this supplies the transport.
    implementation(project(":modules:law-enforcement"))
    implementation(libs.kafka.clients)

    implementation(libs.picocli)
    implementation(libs.bundles.jackson)
    runtimeOnly(libs.logback.classic)

    // An ingest reaching silver needs a real object store. Tagged "docker" and excluded from the
    // everyday loop; run with -Pdocker.
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.minio)
    testImplementation(libs.aws.s3)
}

// The platform version has to reach the running CLI, because content declares the range it
// supports and the check is meaningless without knowing what is running (spec section 7).
//
// Stamped into a resource rather than only the jar manifest: a jar manifest is absent when the CLI
// runs from a class directory, which is how every test and every `gradlew run` executes it. A
// version that vanished in those cases would refuse all content on a developer machine.
tasks.named<ProcessResources>("processResources") {
    val platformVersion = project.version.toString()
    inputs.property("platformVersion", platformVersion)
    filesMatching("niem-platform.properties") {
        expand("platformVersion" to platformVersion)
    }
}

tasks.named<Jar>("jar") {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

application {
    applicationName = "niem"
    mainClass.set("gov.niemplatform.cli.NiemCli")
}

/**
 * One test runs the installed binary in its own process.
 *
 * The bugs it guards against lived in the start script and the JVM flags, neither of which exists
 * when a test calls a method, and it needs a real environment to deliver the object store secret the
 * way production does. So it needs the distribution to exist and to know where it is.
 */
tasks.named<Test>("test") {
    dependsOn(tasks.named("installDist"))
    systemProperty(
        "niem.install.dir",
        layout.buildDirectory.dir("install/niem").get().asFile.absolutePath,
    )
}

/**
 * Windows start script: use a wildcard classpath rather than every jar spelled out.
 *
 * The application plugin writes an explicit `set CLASSPATH=...` listing every dependency. With
 * Flink, Parquet, Hadoop and Iceberg on the path that comfortably exceeds cmd.exe's 8191-character
 * command line limit, and `niem.bat` fails with "The input line is too long" before it reaches
 * main(). A wildcard entry is equivalent here -- everything the CLI needs is in lib/ -- and is what
 * makes the CLI usable on an operator's Windows workstation at all.
 */
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        windowsScript.writeText(
            windowsScript.readText()
                // Lambda replacement: backslashes are literal. The string form would treat them
                // as escapes and silently produce "%APP_HOME%lib*".
                .replace(Regex("(?m)^set CLASSPATH=.*$")) { "set CLASSPATH=%APP_HOME%\\lib\\*" }
        )
    }
}
