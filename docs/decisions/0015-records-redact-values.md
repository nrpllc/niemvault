# 0015. Record `toString` never exposes values

**Status:** Accepted · decision taken during implementation

## Context

Records flowing through the mapping DAG carry criminal justice PII: names, dates of birth,
Social Security Numbers, driver licence numbers. Spec §9 requires structured logging.
Structured logging still serialises whatever it is handed.

The realistic failure is not a deliberate disclosure. It is a `log.debug("processing {}",
record)` added during an incident, shipped, and then quietly writing SSNs into a log
aggregator for a year.

## Decision

`Record.toString()` renders the type name and the field *names* only. Values are reachable
only through `values()` and typed accessors -- explicit calls that a reviewer can see.

Contract violation events (§4.7) need a sample of the offending value to be useful. That
sample goes through an explicit redacting sampler rather than through `toString`, so the
decision to include data in an event is always deliberate and always visible at the call site.

## Consequences

- Debugging a record's contents takes an explicit call. That friction is the point.
- Every future type that carries record values -- envelopes, quarantine entries, lineage
  events -- inherits this obligation. It is not enforced by the compiler, so it needs to be a
  review habit and is called out in CLAUDE.md.
