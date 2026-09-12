# 0033 — FTPS is its own connector, and what a pull means is shared

**Status:** Accepted
**Date:** 2026-09-11
**Context:** §4.3 (connectors), §6 (air-gapped delivery), ADR 0027, ADR 0030, ADR 0031
**Implements:** `connectors:pull`, the FTPS connector

## Context

ADR 0031 shipped SFTP and said FTPS would be another connector and another jar, because "the
protocols differ enough that sharing this connector's code would mean a settings surface where half
the keys are inert depending on the other half."

That was right about the settings and wrong to leave it there. It implied the *whole* connector
would be duplicated, and most of an SFTP connector is not SFTP. The `afterDownload` postures, the
watermark comparison, the rule that a file counts as landed only when its last record has been
yielded, the look-ahead that makes that rule independent of the caller's loop — none of those are a
protocol. They are what a pull *means*, and they are the parts where a second, subtly different copy
would be a correctness bug rather than an inconvenience.

## Decision

### 1. A `connectors:pull` module holds what a pull means

`AfterDownload`, `RemoteFile` and its read order, `RecordMode`, and `PullSourceHandle` — the handle
that tracks which files have been drained and acknowledges them after the bronze commit.

The protocol appears behind one small interface, `RemoteDirectory`: list, read, archive, delete, and
is-this-a-directory. That is a pull in any protocol anyone has shipped.

The test that this was the right seam is that the FTPS connector's behavioural suite asserts the
same properties as the SFTP one — same postures, same read order, same "a partly read file is never
archived" — and passes without either connector knowing about the other.

### 2. FTPS is still its own connector

ADR 0031's reasoning stands for the half it was about. SFTP verifies a host key and may authenticate
with a private key; FTPS negotiates TLS, validates a certificate chain, and must be told whether to
open data connections actively or passively. Merging the two would produce a settings surface where
`hostKeyFingerprint` is inert whenever `security` is set and vice versa — and an inert setting is how
an operator comes to believe something is configured when it is not.

### 3. `security` is required, with no default

`explicit`, `implicit`, or `none`. Defaulting to `explicit` would be the safe guess and would still
be wrong: an operator pointing this at a server without TLS gets a negotiation failure, and resolves
it by trying settings until one works. `none` is the one that always works.

Plain FTP sends the credential and then every record in clear text. That is occasionally defensible —
a private circuit, a closed lab — and never defensible by accident, so it is a sentence someone
writes into a reviewable file. The transport is named `ftps` rather than `ftp` for the same reason:
plain FTP is a setting on this transport rather than a transport of its own, so choosing it reads as
the downgrade it is.

### 4. Binary transfer mode, not configurable

FTP defaults to ASCII, which rewrites line endings in flight. A file pulled that way arrives with
bytes the source never sent — and envelope identity is derived from a content hash, so the same file
would land with two identities depending on a transfer mode nobody meant to choose. That defeats the
duplicate detection every re-read posture depends on. Binary is set explicitly and is not offered as
a setting, because there is no correct reason to pick the other one.

### 5. A transfer is not complete until the server says so

FTP reports completion in a reply separate from the data connection closing, so reading to
end-of-stream is not evidence that a file arrived whole. Every read is followed by
`completePendingCommand()`, and a failure there is raised rather than swallowed: the file is not
acknowledged, so it is read again next run. The alternative is a truncated file landed as though it
were complete, which nothing downstream can detect.

## Consequences

- **Apache commons-net** joins the version catalogue, and **Apache FtpServer** as a test-only
  dependency. Both Apache-licensed and mirrorable for §6.
- **The SFTP connector was refactored onto the shared module** rather than left alone. Its 37 tests
  pass unchanged, which is the evidence that the extraction preserved behaviour.
- **FTP's listing timestamps are lossy**, minute-precision at best and year-only for older files on
  some servers. This makes `watermark` weaker over FTP than over SFTP, on top of the late-arrival
  hazard ADR 0031 already documented. `archive` is the better posture here by a wider margin.
- **A file with no listing timestamp sorts to the epoch**, not to now. Sorting it to now would park
  the watermark past files that have not been read, which is the one failure a pull must not have.
- **TLS itself is not tested against a live certificate.** The behavioural suite runs plain FTP over
  loopback; adding a self-signed certificate would exercise the JDK's trust store rather than this
  connector. The TLS paths are covered by configuration tests, and that boundary is stated here
  rather than left for someone to discover.
