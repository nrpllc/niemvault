# 0028 — A streaming source lands in bounded slices, and acknowledgement follows the commit

**Status:** Accepted
**Date:** 2026-09-09
**Context:** §4.3 (connectors), §4.4 (bronze), §8 (Phase 2), ADR 0027
**Implements:** the Phase 2 Kafka connector

## Context

Phase 1 shipped one transport, a file drop, and it is the easy one in every respect that matters
here. A directory is finite, so a read ends on its own. It remembers nothing, so re-reading it is
harmless. And it carries an agency's own data by construction — a file the agency placed in its own
drop directory — so its retention posture is a property of the transport.

A topic is none of those things. It has no end, it holds a read position on the source side, and the
same broker carries an agency's own CAD feed on one topic and a state system's non-retainable
responses on another.

`LandingService` drains a `SourceHandle` to completion. That is the design, and it is what makes
"every connector lands into bronze identically" (§4.3) true rather than aspirational. A connector
whose handle never completed would break it.

## Decision

### 1. An `open()` reads a bounded slice, not a subscription

A slice ends at whichever comes first: a record ceiling, or a quiet period with nothing arriving. It
is a consumer group, so the next `open()` resumes where the last one stopped, and a scheduled ingest
makes a continuous feed out of repeated bounded reads.

The alternative — an unbounded landing loop — would have to make its own decisions about commit
cadence, back-pressure and shutdown. Every one of those is a second answer to a question
`LandingService` already answers for every other transport, and the second answer is the one that
would eventually differ. §4.3 requires transport differences not to leak past the landing boundary;
an endless stream leaks the largest one there is.

Both bounds are needed. Without the ceiling, a first read of a large backlog never returns. Without
the idle window, a caught-up feed blocks forever on a topic that is merely not busy.

### 2. `SourceHandle.acknowledge()`, called after the bronze commit and never before

A new method on the SPI, a no-op by default because for a file drop it genuinely is one. A transport
that holds a position on the source side advances it there and nowhere else.

The ordering only goes one way:

- **Acknowledge before the commit** and a crash *loses records*. The source believes it has
  delivered them and will not send them again; bronze does not have them. Nothing detects this.
- **Acknowledge after the commit** and the same crash *duplicates records*. Bronze already detects
  this: envelope identity is derived from source, offset and content hash, so a redelivered record
  lands with the identity it had the first time.

One of those failures is recoverable and the other is not, so the choice is not a trade-off.

Auto-commit is switched off explicitly for the same reason, and not as a tuning choice: a consumer
committing on its own schedule can acknowledge records bronze never received.

The subtle half is *what* may be acknowledged. A poll returns up to 500 records at once and the
consumer's position jumps to the end of them immediately, while the caller may have taken only the
first hundred. So the handle commits the furthest offset actually **yielded**, not the consumer's own
position. A bare `commitSync()` would silently acknowledge four hundred records nobody had seen.

### 3. Retention posture is per source, not per transport

ADR 0027 requires every connector to declare whether its records may be kept, with no default. The
file drop connector can answer from the transport alone. Kafka cannot, and must be told: `retention`
is a required setting, and `retention()` before `configure()` throws rather than guessing.

The only assumption that would let a run proceed is `RETAINED`, and arriving at an unlawful retention
by omission is precisely what ADR 0027 exists to prevent.

### 4. A simulator, because a streaming source cannot be exercised with a file

The file drop connector could be demonstrated with a fixture. A streaming one cannot: a source that
keeps arriving, a slice resuming where the last stopped, identity resolution meeting the same human
an hour later, and a schema drifting mid-flight are all invisible in a static file.

So `niem simulate` publishes a synthetic CAD feed to a topic. Deterministic from a seed, so a run can
be checked against an expected result and a bug that appeared once can be reproduced. Every person in
it is invented (ADR 0013), and the generator holds no path by which real data could reach it.

It is a **separate command from `run`**, deliberately, and not a mode of it. A simulator that can be
switched on inside an ingest is one that can be switched on by accident against a real bronze store,
and synthetic records mixed into an agency's landed data cannot be taken back out.

## Consequences

- **A Kafka ingest is a scheduled job, not a daemon.** The Helm chart's ingest CronJob shape already
  fits it. A deployment wanting lower latency shortens the schedule; it does not need a different
  execution model.
- **At-least-once, stated plainly.** Duplicates are possible and detectable. Exactly-once would
  require the bronze commit and the offset commit to be one transaction across two systems, which is
  not available here and would be a much larger claim than the platform can honestly make.
- **A slice boundary is visible in bronze** as a batch boundary, which is what makes a partial
  landing diagnosable after a failure.
- **Every future positioned transport inherits this.** CDC has a log sequence number and FTP has an
  archive move; both are `acknowledge()` and both get the ordering for free. A connector author who
  forgets to override it re-delivers everything on every open — visible and safe — rather than losing
  records, which is neither.
- **Still open:** whether a transient Kafka topic — one carrying non-retainable responses — should be
  readable at all through a connector whose whole shape assumes landing. `LandingService` refuses it
  today, correctly, but that leaves the topic unreadable rather than readable-without-landing. The
  query path ADR 0027 anticipates is where that belongs, and it is not built.
