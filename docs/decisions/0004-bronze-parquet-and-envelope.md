# 0004. Bronze as append-only Parquet with a JSON envelope

**Status:** Accepted · pinned by spec §2 and §4.4

## Context

Silver and gold must be fully rebuildable from bronze. That is both a correctness property
and an audit requirement: an auditor asking what a source actually sent, and what the platform
did with it, must be answerable from stored bytes rather than from inference.

## Decision

Bronze is an append-only object store layout of Parquet files. Every landed record is wrapped
in an envelope carrying source identifier, connector instance, ingest timestamp,
source-asserted timestamp, byte-preserved raw payload, content hash, and batch or stream
offset. The envelope is written alongside the payload, and a sidecar JSON manifest per batch
records what was landed so bronze is inspectable without a Parquet reader.

Bronze is never mutated or deleted by platform logic. There is no code path that updates a
bronze record; retention deletion, when it arrives, will be an operator action with its own
audit trail, not a pipeline behaviour.

## Consequences

- The payload is stored as bytes, not as parsed fields. A source that changes its format does
  not corrupt what was already landed, and replay under a new mapping reinterprets the
  original bytes rather than an earlier interpretation of them.
- The content hash makes duplicate landing detectable without re-reading payloads.
- Parquet is written through parquet-java's local file abstraction rather than a Hadoop
  `FileSystem`, so bronze works on a developer's Windows machine and in an air-gapped
  container without a Hadoop installation.
