pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "niem-platform"

include(
    "core:canonical",
    "core:contracts",
    "core:lineage",
    "runtime:engine",
    "runtime:transforms",
    "runtime:replay",
    "connectors:api",
    "connectors:file",
    "identity:api",
    "identity:internal",
    "projections:api",
    "projections:graph",
    "modules:law-enforcement",
    "tools:cli",
)
