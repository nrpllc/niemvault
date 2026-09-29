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
    "core:settings",
    "storage",
    "runtime:engine",
    "runtime:transforms",
    "runtime:replay",
    "connectors:api",
    "connectors:file",
    "connectors:kafka",
    "connectors:pull",
    "connectors:sftp",
    "connectors:ftp",
    "identity:api",
    "identity:internal",
    "exchange:api",
    "exchange:cch",
    "projections:api",
    "projections:graph",
    "projections:ods",
    "projections:search",
    "pipeline",
    "modules:law-enforcement",
    "control-plane",
    "tools:cli",
)
