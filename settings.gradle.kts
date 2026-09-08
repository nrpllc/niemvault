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
    "core:niem",
    "core:canonical",
    "core:contracts",
    "core:observability",
    "core:content",
    "core:lineage",
    "core:disclosure",
    "storage",
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
    "control-plane",
    "tools:cli",
)
