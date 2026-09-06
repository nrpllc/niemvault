import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
}

// Precompiled script plugins do not get the generated `libs` accessor, so the catalogue is
// resolved through the public API instead. Same catalogue, same single source of versions.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).orElseThrow {
    IllegalStateException("Version catalogue has no library alias '$alias'")
}

fun version(alias: String) = libs.findVersion(alias).orElseThrow {
    IllegalStateException("Version catalogue has no version alias '$alias'")
}.requiredVersion

group = "gov.niemplatform"
version = providers.gradleProperty("platformVersion").get()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(version("java").toInt()))
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(version("java").toInt())
    options.encoding = "UTF-8"
    // -parameters keeps record component names available to config binding and diagnostics.
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-parameters"))
}

dependencies {
    implementation(lib("slf4j-api"))

    testImplementation(platform(lib("junit-bom")))
    testImplementation(lib("junit-jupiter"))
    testImplementation(lib("assertj-core"))
    testRuntimeOnly(lib("junit-platform-launcher"))
    testRuntimeOnly(lib("logback-classic"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Flink's serialisation stack reflects into JDK internals, which Java 21 denies by
    // default. These opens are what let embedded Flink run in-process during tests.
    jvmArgs(
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
        "--add-opens=java.base/java.time=ALL-UNNAMED",
        "--add-opens=java.base/java.math=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    )
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
}
