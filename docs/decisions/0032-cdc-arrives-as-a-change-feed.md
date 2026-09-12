# 0032 — Change data capture arrives as a change feed, not as a CDC connector

**Status:** Accepted
**Date:** 2026-09-11
**Context:** §4.3 (connectors), §6 (air-gapped delivery), ADR 0027, ADR 0028, ADR 0030
**Implements:** the CDC source recipe (`riverton-rms-cdc.yaml`)

## Context

CDC is the third transport agencies ask for, and the first one where the obvious implementation is
the wrong one.

The obvious implementation is a `connectors:cdc` module embedding Debezium's engine, so the platform
reads the write-ahead log itself. It would work. What it costs:

- **A large dependency tree to mirror.** Debezium plus a database driver per engine — Postgres, SQL
  Server, Oracle, MySQL — every one of which has to be mirrorable into an offline repository for the
  air-gapped mode (§6). An agency that runs one database would still be carrying the others.
- **A second answer to a question already answered.** The embedded engine owns its own offset commit
  and its own threading. ADR 0028 put commit ordering in exactly one place — `acknowledge()` after
  the bronze commit — and bending the engine's callback into that shape means reimplementing it.
- **An operational surface this platform does not want.** Log reading needs a replication slot, a
  publication, and a privileged database account. Whether that connection is healthy is a question
  about the agency's RMS, and putting it inside the ingest process makes a database problem look like
  a platform problem at 2am.

Meanwhile the deployed reality: **Debezium is already how CDC is run**, and it already writes change
events to Kafka.

## Decision

CDC is not a transport this platform implements. Debezium runs as its own process and publishes
change events to a topic; the platform reads that topic with the Kafka connector it already has.

The source definition says `type: kafka`, and that is not a workaround. By the time the records reach
this platform it genuinely *is* Kafka — the log reading happened somewhere else, under someone else's
operational ownership, which is where a replication slot belongs.

What this buys, in ADR 0028's terms: the two positions stay independent. Debezium keeps its log
position in its own offsets topic; this platform keeps its read position in a consumer group. This
platform re-reading the topic does not make the database re-emit anything, and a Debezium restart
does not disturb what has landed in bronze.

### The trap, which is the reason this ADR exists rather than a paragraph in a README

Two Debezium settings change what arrives, and one of them is a silent failure:

```
tombstones.on.delete = false
snapshot.mode        = initial
```

With `tombstones.on.delete` **on** — which is Debezium's default — every delete is followed by a
tombstone: a record with a key and a null value. This platform's Kafka connector lands a null value as
an empty payload rather than skipping it, because a skipped record would break the completeness
accounting that exists to prove nothing goes missing. But it does not land the *key*, deliberately: a
key is transport addressing, like a filename, and a key carrying meaning the mapping needs is a
producer putting data where the record cannot see it.

Both of those decisions are right on their own, and together they mean a tombstone arrives as an
empty payload with nothing to say which row it was — a record that is real, unattributable, and
quarantined on every run.

With it **off**, the delete arrives as an ordinary change event with `op=d` and a `before` image
carrying the row that was deleted: mappable, and the thing an investigator actually needs.

`snapshot.mode: initial` for the matching reason — without it the topic begins with whatever changes
next, and bronze holds a change feed for rows it has never seen the first version of.

## Consequences

- **No new module, no new dependency.** The work is a source definition, this record, and the
  operator documentation.
- **A change event's envelope is the mapping's problem**, exactly as a CSV row's columns are for the
  file drop. Turning `before`/`after`/`op` into canonical records is schema mapping, the half of
  source onboarding where the value is (§4.3). The connector stays ignorant of it, which is what lets
  the same source be re-onboarded over a nightly extract if the agency ever withdraws log access.
- **One source definition per table.** Debezium's topic naming is
  `<topic.prefix>.<schema>.<table>`, and a table is what a mapping is written against.
- **`freshnessSla` becomes meaningful and strict.** Debezium stamps each event with the
  transaction's commit time, which arrives as the producer's create time — a real assertion about
  when the change happened in the source system, not when this platform got to it. A stalled Debezium
  connector therefore shows up as a `PipelineLag` (§4.7) rather than as a quiet topic.
- **An agency that cannot run Debezium is not served by this.** If log access is refused outright,
  the answer is a scheduled extract over SFTP (ADR 0031) or a file drop, and the same mapping serves
  it. If Debezium is acceptable but Kafka is not, that is the case that would justify the embedded
  engine — and it should be reopened then, with that deployment's constraints in hand, rather than
  guessed at now.
