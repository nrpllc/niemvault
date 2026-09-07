# 0005. Table format for canonical silver storage

**Status:** Accepted · spec §2 permits Delta Lake or Iceberg; Iceberg selected, both halves verified

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

## Update — the Parquet-on-Windows question is settled for bronze

Spiked and answered (`ParquetOnWindowsSpikeTest`). Parquet writes and reads correctly on
Windows with no Hadoop installation and no `winutils.exe`, provided every file access goes
through parquet-java's NIO abstractions -- `LocalOutputFile` and `LocalInputFile` -- rather
than a Hadoop `FileSystem`.

Two Hadoop artifacts are still needed on the classpath, and only on the classpath:

- `hadoop-common`, because Parquet's builder API takes a `Configuration`;
- `hadoop-mapreduce-client-core`, because `ParquetReadOptions` statically references a
  MapReduce input format class that is never invoked but must resolve.

Neither is used at runtime. Bronze therefore ships as ADR 0004 specifies, and the local
development loop stays intact.

**Silver is still open.** Iceberg's catalog and `FileIO` implementations reach for a Hadoop
`FileSystem` rather than merely a `Configuration`, which is a materially harder problem than
the one just solved. That spike is still owed, and the question for Jeff stands unchanged.

## Resolved — Iceberg runs on an object store, with no Hadoop and no deviation

Answered by `IcebergOnObjectStoreSpikeTest`, which passes: a JDBC catalog for metadata and
`S3FileIO` for data, against MinIO in a container. Iceberg creates, appends to, and reads a table,
and **snapshot time travel works** -- which is the property spec §2 cited as the reason for
pinning a table format at all, and the property acceptance criterion 6 depends on.

No Hadoop `FileSystem` is constructed anywhere on that path, so `winutils.exe` never enters into
it and the pinned §2 decision stands unchanged. An object store is also what §2 names for storage
in the first place, and it is what an agency will actually run -- including on premises, which
matters for the air-gapped delivery mode.

**Silver therefore ships as Iceberg.** The `CanonicalStore` interface still stands between the
platform and it, per ADR 0018, so nothing above `storage.api` sees an Iceberg type.

### What actually blocked this, for the record

Three wrong diagnoses preceded the right one, and the wrong ones are worth naming because each
looked convincing:

1. *Iceberg cannot run on Windows.* True of `HadoopCatalog`, irrelevant once the catalog and file
   IO avoid Hadoop entirely.
2. *Docker Desktop is not exposing its socket.* Wrong. Both `\.\pipe\docker_engine` and
   `\.\pipe\dockerDesktopLinuxEngine` accept connections; the "Allow the default Docker socket"
   toggle that would have fixed it does not exist on Windows.
3. *`DOCKER_HOST` is not reaching the test JVM.* Half true -- a Gradle test JVM inherits the
   daemon's environment rather than the shell's -- but not the cause.

The cause was a version floor: **Docker Engine 29 refuses API versions below 1.40, and the
docker-java client in Testcontainers 1.20.4 requests v1.32.** The engine answers HTTP 400 with an
empty `/info` body, which Testcontainers reports as "no valid Docker environment" rather than as a
version rejection. Upgrading to Testcontainers 1.21.4 fixed it outright.

The diagnostic that would have found this immediately: compare
`docker version --format '{{.Server.MinAPIVersion}}'` against the `GET /vX.YY/info` line in the
Testcontainers debug log.
