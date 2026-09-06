# 0003. Gradle with the Kotlin DSL

**Status:** Accepted · pinned by spec §2

## Context

A multi-module JVM build with code generation, and an air-gapped delivery mode that requires
every dependency to be mirrorable into an offline repository.

## Decision

Gradle 8.12 with the Kotlin DSL. A version catalogue at `gradle/libs.versions.toml` is the
single source of dependency versions. Shared build behaviour lives in convention plugins under
`build-logic/`, applied explicitly per module, rather than in an `allprojects` block.

The wrapper is generated with `--gradle-distribution-sha256-sum` so the pinned distribution
checksum is verified on every build, on every machine.

## Consequences

- A module's build file states what that module is, instead of inheriting behaviour invisibly.
- No dynamic versions and no snapshots in the catalogue: an air-gapped mirror cannot resolve
  either, and a build that only works online is a build that does not work.
- Precompiled script plugins do not receive the generated `libs` accessor, so
  `niem.java-conventions` resolves the catalogue through `VersionCatalogsExtension` instead.
  Same catalogue, one indirection.
