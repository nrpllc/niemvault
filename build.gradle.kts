// Root build. Deliberately thin: every module opts into behaviour through the convention
// plugins in build-logic rather than inheriting it from an allprojects/subprojects block,
// so a module's build file states what that module actually is.

tasks.register("canonicalModel") {
    group = "canonical model"
    description = "Validates and regenerates every canonical model in the build."
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("generateCanonicalTypes") })
}
