# 0008. Warehouse projection

**Status:** Deferred · pinned by spec §2, scheduled for Phase 3

## Context

BI, analytics, and ML feature engineering want a relational star schema. None of those are
Phase 1 or Phase 2 concerns.

## Decision

A relational/star-schema warehouse projection is built in Phase 3, behind `ProjectionWriter`.

## Consequences

- Gold is polymorphic by design: graph, search, and warehouse are three projections of one
  canonical silver source, not three pipelines.
- This is the projection most likely to want Spark or warehouse-native SQL rather than
  per-record writes, which is the trigger for revisiting ADR 0002.

**Revisit when:** Phase 3 begins.
