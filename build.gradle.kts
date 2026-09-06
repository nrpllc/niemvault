// Root build. Deliberately thin: every module opts into behaviour through the convention
// plugins in build-logic rather than inheriting it from an allprojects/subprojects block,
// so a module's build file states what that module actually is.

tasks.register("canonicalModel") {
    group = "canonical model"
    description = "Validates and regenerates every canonical model in the build."
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("generateCanonicalTypes") })
}

/**
 * Runs every test in the build, including the included `build-logic` build.
 *
 * `gradlew test` alone misses `build-logic`, which holds the canonical model code generator --
 * the component with the most rules per line in the project. A green run that silently skipped
 * it would be worse than no run.
 *
 * The Zen sidecar's `zen-test.mjs` merges the per-class JUnit XML this produces into
 * `.zen/test-report.xml`; that merge is its job, not this build's.
 */
tasks.register("testAll") {
    group = "verification"
    description = "Runs every test in this build and in the included build-logic build."
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("test") })
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
}
