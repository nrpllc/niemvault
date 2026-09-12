# 0031 — A pull transport states who remembers what it landed

**Status:** Accepted
**Date:** 2026-09-11
**Context:** §4.3 (connectors), §6 (air-gapped delivery), ADR 0027, ADR 0028, ADR 0029, ADR 0030
**Implements:** the SFTP connector

## Context

The transport an agency is actually offered, when a state or county system will not publish to a
topic and will not let anyone near its database, is a directory on a server: a credential, an export
written on a schedule, and nothing else.

Phase 2 covered the push case. Kafka arrives when the source has something, holds its position in a
consumer group, and ADR 0028 settled the ordering. A pull is not a symmetrical problem with the
arrows reversed — it asks one question a push never has to:

**Nobody remembers that the directory was read.**

Kafka handed that to the broker. A file drop sidesteps it by re-reading every time, which is safe
because envelope identity makes the duplicates detectable. Neither answer transfers: an SFTP pull
against somebody else's export directory can neither delegate to a consumer group nor casually
re-download a year of exports every night.

## Decision

### 1. `afterDownload` is required, with no default

Four answers, and the operator picks one:

| | Who remembers | Needs | Can get wrong |
|---|---|---|---|
| `archive` | the remote server | write access | nothing this platform can disagree with |
| `delete` | the remote server | write access | destroys the source's own copy |
| `watermark` | this platform (ADR 0030) | a checkpoint store | silently skips a late file stamped old |
| `none` | nobody | nothing | re-lands everything, every run |

No default is right everywhere, and every candidate default is wrong somewhere that matters:

- `archive` is the strongest and needs write access to a server the agency may not own.
- `none` is the safe-by-duplication posture the file drop already has, and on a directory nobody
  prunes it grows bronze without limit.
- `watermark` is the only read-only option that does not re-land, and it is the one that can **skip**
  — the watermark is (modification time, name), so a file uploaded late but stamped older than the
  last one landed sorts behind it and is never pulled. Nothing reports it. The record simply never
  arrives.

That last failure is the reason this setting has no default. A skip is invisible, and a default that
can skip is a default that is wrong in silence. Making the operator write the word is what puts the
choice, and the trade-off behind it, in a reviewable file.

### 2. A file counts as landed only when its last record has been yielded

The same subtlety ADR 0028 found in Kafka, in a sharper form. `acknowledge()` is called after each
bronze batch, and a batch boundary falls wherever it falls — usually mid-file. Archiving, deleting or
watermarking a file whose remaining lines are still unread would discard records bronze never
received, and no later run would look for them: the file is gone from the directory, or sorts behind
the watermark.

So a file joins the acknowledgeable set only when its last record has been handed over, and a run
that dies mid-file leaves that file exactly where it was. The next run re-reads it from the start and
re-lands what the dead run had committed — duplicates, which bronze detects.

The iterator reads one record ahead so that "drained" is discovered as the last record is handed
over, rather than when the consumer asks for one more and is told no. Those differ only for a caller
that stops at the last record without asking again — and the difference is a file that was fully
landed and never archived, so the next run lands it twice. `LandingService` happens to ask again;
making the guarantee depend on the shape of the caller's loop is the kind of thing that holds until
someone writes a different loop.

### 3. Host key verification is required, with an explicit way to opt out

`hostKeyFingerprint`, or `knownHostsPath`, or `hostKeyCheck: accept-any` — one of them, stated. No
permissive default.

An unverified host key means the session can be terminated by anything that can answer on that
address, and what is handed over is a credential to another agency's system followed by whatever that
system exports. "Accept any" stays available, because a closed lab network is real, but it is a
sentence someone has to write down in a file that gets reviewed.

### 4. Retention is required, as it is for Kafka

ADR 0027's rule, and the case for it is sharper here than for a topic. This connector exists to reach
*somebody else's* server, and the export sitting on it is as likely to be a state system's response
set, held under that state's rules, as it is to be the agency's own extract.

### 5. Ordering is (modification time, name)

A total order, the same one every run, because `watermark` encodes a position in exactly these terms
— a run that read in a different order could leave a watermark ahead of a file it never read. Time
before name, because a source that names exports by content rather than by date has no useful name
order, and time is what a watermark actually means.

## Consequences

- **Apache MINA sshd** joins the version catalogue. Apache-licensed and mirrorable for §6, and it
  ships an embeddable server, so the connector's tests speak real SFTP over a loopback socket instead
  of mocking the protocol they exist to exercise.
- **Archiving onto a name that is taken appends the landing time.** A source that exports `cad.csv`
  every night is the common case, and both alternatives are bad: overwriting destroys last night's
  archived copy, failing stops the run dead on the second night.
- **`health()` checks the archive directory too.** Otherwise a missing one fails at acknowledgement
  time, which is after the records are in bronze and after the run has reported success.
- **Offsets keep the file-drop shape**, `<file>#<line>`. A source that moves from a nightly drop to a
  pull keeps comparable offsets, so a bronze range spanning the move still means one thing.
- **FTPS and plain FTP are not implemented.** The protocols differ enough that sharing this
  connector's code would mean a settings surface where half the keys are inert depending on the
  other half. If a source needs one, it is another connector and another jar — which is what §4.3 is
  for.
