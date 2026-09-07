import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    // java-library, not java: modules expose types from their dependencies across boundaries
    // (a contract's Schema is built from a canonical descriptor), so `api` vs `implementation`
    // is a distinction this build needs to be able to make.
    `java-library`
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

// Module coordinates must be unique, and the directory name alone is not.
//
// Several modules live in directories called "api" -- connectors/api, identity/api,
// projections/api -- and a project's Gradle name is its directory name. All three therefore
// resolved to gov.niemplatform:api, which Gradle treats as one module and silently substitutes:
// a dependency on :connectors:api resolved to :identity:api, and the only symptom was a package
// that "does not exist". Deriving the group from the parent path makes the collision impossible.
val parentPath = project.parent?.path?.removePrefix(":")?.replace(':', '.').orEmpty()
group = if (parentPath.isEmpty()) "gov.niemplatform" else "gov.niemplatform.$parentPath"
version = providers.gradleProperty("platformVersion").get()

base {
    archivesName.set("niem" + project.path.replace(':', '-'))
}

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

val runDockerTests = providers.gradleProperty("docker").isPresent

/**
 * Opens embedded Flink needs on Java 21.
 *
 * Flink's serialisation stack reflects into JDK internals, which Java 21 denies by default. Declared
 * once and applied to both tests and the shipped application: the CLI runs Flink in-process exactly
 * as the tests do, so a binary without these is one that passes CI and fails on an operator's
 * machine. Two lists would drift, and the drift would only show up at runtime.
 */
val flinkOpens = listOf(
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
    "--add-opens=java.base/java.time=ALL-UNNAMED",
    "--add-opens=java.base/java.math=ALL-UNNAMED",
    "--add-opens=java.base/java.text=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
)

// Applied wherever the application plugin is, so the start script and the container image carry the
// same flags the tests run with.
plugins.withId("application") {
    extensions.configure<JavaApplication>("application") {
        applicationDefaultJvmArgs = flinkOpens
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        // Tests tagged "docker" need a container runtime. Excluded by default so the everyday
        // loop stays fast and works on a machine with no Docker; run them with -Pdocker.
        // ADR 0005: the silver store's tests live behind this tag.
        if (!runDockerTests) {
            excludeTags("docker")
        }
    }
    jvmArgs(flinkOpens)
    // Docker Engine 29 refuses API versions below 1.40, and the docker-java client Testcontainers
    // 1.20.4 ships requests v1.32. The engine answers with HTTP 400 and an empty /info payload, so
    // Testcontainers reports "Could not find a valid Docker environment" on a machine where the
    // Docker CLI works perfectly. Pinning a supported version is the fix; the exact number matters
    // only in that it sits between the engine's minimum and its current version.
    //
    // Set on the task rather than exported from a shell: a Gradle test JVM inherits the daemon's
    // environment, not the environment of whoever typed the command, so exporting it in a terminal
    // has no effect on an already-running daemon.
    if (System.getenv("DOCKER_API_VERSION") == null) {
        environment("DOCKER_API_VERSION", "1.44")
    }

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
}
