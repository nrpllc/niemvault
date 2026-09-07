plugins {
    id("niem.java-conventions")
    application
}

description = "Operator CLI (spec §8): validate, run, inspect."

dependencies {
    implementation(project(":runtime:engine"))
    implementation(project(":connectors:file"))
    implementation(project(":identity:internal"))
    implementation(project(":storage"))

    implementation(libs.picocli)
    implementation(libs.bundles.jackson)
    runtimeOnly(libs.logback.classic)

    // The CLI's tests drive it against the artifacts the law enforcement module ships, so a
    // broken contract or mapping fails here as well as in that module's own tests.
    testImplementation(project(":modules:law-enforcement"))
}

application {
    applicationName = "niem"
    mainClass.set("gov.niemplatform.cli.NiemCli")
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
