plugins {
    `kotlin-dsl`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
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
