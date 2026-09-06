# 0005. Table format for canonical silver storage

**Status:** Proposed · spec §2 permits Delta Lake or Iceberg

## Context

Spec §2 pins canonical storage to "Delta Lake (or Iceberg) tables", with time travel cited as
the reason: it is what makes lineage and replay verifiable rather than asserted.

Phase 1 needs three properties from silver and no more: atomic commit of a mapping run,
addressable historical versions so replay output can be compared against the original, and
full rebuild. It does not need Spark, and Phase 1 explicitly excludes it (ADR 0002).

## Decision

Silver sits behind a `CanonicalStore` interface. Iceberg is the intended Phase 1
implementation, chosen over Delta Lake because its Java write path is mature standalone --
Delta's Java kernel writer is narrower and in practice assumes Spark for anything beyond
simple appends.

**This is not yet validated.** Iceberg's catalog and file IO implementations pull in
`hadoop-common`, which on Windows historically requires `winutils.exe` for local filesystem
operations. If that proves true here, the options are to run silver only in containers
(degrading the local development loop), or to implement the `CanonicalStore` interface over
Parquet with an explicit JSON snapshot log -- which supplies exactly the atomic-commit and
time-travel properties Phase 1 needs, but deviates from a pinned decision.

## Consequences

- Nothing above the `CanonicalStore` interface may reference Iceberg types, or the fallback
  above stops being available.
- **Open, for Jeff:** if Iceberg cannot run embedded on a developer machine, is the deviation
  acceptable for Phase 1, or is containerised-only local development preferred? Do not resolve
  this unilaterally.

**Revisit when:** the Iceberg embedded spike completes, or Phase 2 introduces Spark.
