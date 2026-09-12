# 0030 — A read position the source does not hold is kept by the platform

**Status:** Accepted
**Date:** 2026-09-11
**Context:** §4.3 (connectors), §4.4 (bronze), §6 (air-gapped delivery), ADR 0028, ADR 0029
**Implements:** `SourceCheckpointStore`, `FileSourceCheckpointStore`

## Context

ADR 0028 established the ordering that keeps a streaming source honest: acknowledge after the bronze
commit, never before. `SourceHandle.acknowledge()` is where a transport advances its position.

For Kafka that was the whole story, because the position was never this platform's problem. A
consumer group *is* the read position, it lives on the broker, and `acknowledge()` commits it. The
file drop connector needed nothing either, for the opposite reason: it has no position at all, it
re-reads the directory every run, and that is safe because envelope identity is derived from source,
offset and content hash, so the duplicates are exactly detectable.

Both of those are lucky. The next two transports are not:

- An **SFTP pull** reaches a directory on somebody else's server. The directory does not remember
  that anyone read it.
- A **change feed** has a log position that the reader carries.

For these, `acknowledge()` is a hook with nothing behind it. A position kept in a field on the
connector is a position that resets to the beginning of time when the process exits — which for a
nightly pull means a second full copy landed in bronze every night, arrived at silently, with every
log line reporting success.

## Decision

### 1. A `SourceCheckpointStore`, written only from `acknowledge()`

A two-method store — read a position, write a position — plus `clear` for a deliberate re-ingest and
`readAll` for an operator asking where each source stopped.

It inherits ADR 0028's ordering rather than restating it. `LandingService` calls `acknowledge()` only
after `append()` returns, so a connector that writes its position there is correct by construction; a
connector that writes from anywhere else has stepped outside the rule, and the store cannot stop it.

### 2. The position is opaque text

Same reasoning that makes `ConnectorType` a value type rather than an enum and connector settings
untyped strings: a transport this platform has never seen must be able to checkpoint without changing
a platform class. An SFTP connector writes a filename watermark, a change feed a log sequence number,
and neither shape means anything to the platform. Only the connector that wrote it interprets it.

It is deliberately **not** a `SourceOffset`. That type is ordered, and bronze ranges over it. A
checkpoint is a bookmark, not an offset, and giving it an ordering the platform did not define would
invite code that compared two transports' positions.

### 3. Files, not a database

Spec §6 describes an air-gapped delivery mode and §4.3 makes a connector a jar someone ships. A
position store that needed a schema migration in somebody's Postgres would turn adding a transport
into an operations project. A directory works in a container with a mounted volume, on a laptop, and
in an install with no database, and an operator can read it with `cat` during an incident — which is
exactly when they want to know how far a source got.

Writes go to a temporary file, are forced to disk, and are then moved onto the real name. A crash
mid-write leaves the old position or the new one, never half of either. A truncated position file
would be worse than no file: absent means "start from the beginning", which is safe and merely
duplicative, while corrupt could be read as a position ahead of anything that actually landed. For
the same reason an unparseable file is an error rather than a silent restart from zero — re-reading a
source is a decision an operator makes with `clear`, not one a damaged file makes for them.

### 4. Absent is refused, never quietly substituted

A connector that needs a position and was given nowhere to put it is refused at `configure` time, via
`SourceCheckpointStore.unavailable()` and `requireUsable`. This follows the `KafkaConnector.retention()`
precedent exactly: the alternative is a run that succeeds and is wrong.

Handing out a working in-memory store when none was configured would have been the worst option. The
connector accepts it, the run reports success, and the position is lost at exit.

Every connector is offered a store, including the ones that ignore it. Offering it only to transports
believed to need one would put the platform in the business of knowing which those are, and the next
connector shipped as a jar is precisely the one it would not know about.

## Consequences

- **`niem run --checkpoints <dir>`** is how a deployment provides one. Optional, because most
  transports do not need it and demanding it on every file-drop run would be noise.
- **A position is per `(sourceId, connectorInstanceId)`**, not per source. Two instances of one
  transport read independently, and sharing a position would have each skip what the other landed.
- **The filename is sanitised text plus a hash of the exact pair.** Sanitising alone maps
  `riverton/cad` and `riverton:cad` to one file, and one connector then reads the other's position.
- **Nothing in bronze changes.** A checkpoint is not lineage and is not an input to replay: it says
  where reading stopped, not what was read. What was read is in bronze, which is still the only
  record of that.
