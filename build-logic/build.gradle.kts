plugins {
    `kotlin-dsl`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

/*
 * The NIEM manifest parser is shared source, not a copy.
 *
 * The code generator verifies provenance against a release at build time and the coverage browser
 * reads the same manifests at run time. Two parsers for one format would drift, and the drift would
 * surface as a coverage report disagreeing with what the build enforces -- the failure ADR 0020
 * names for artifact formats generally. build-logic is an included build, so it cannot depend on a
 * module of the main build; compiling the one file is how the two stay identical.
 */
sourceSets.main {
    java.srcDir("../core/niem/src/main/java")
}

dependencies {
    // The canonical-model code generator (spec §4.1) lives in this build so generated Java
    // records are a build product of the DSL rather than hand-maintained sources.
    implementation(gradleApi())
    implementation(libs.snakeyaml)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("canonicalCodegen") {
            id = "niem.canonical-codegen"
            implementationClass = "gov.niemplatform.build.canonical.CanonicalCodegenPlugin"
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
