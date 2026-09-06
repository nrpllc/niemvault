# 0002. Spark or warehouse SQL for silver to gold

**Status:** Deferred · pinned by spec §2, explicitly out of Phase 1 scope

## Context

Silver-to-gold is batch aggregation, reshaping, and large joins. Flink's optimiser is weaker
than Spark's for large shuffles, and forcing it there would trade a real performance loss for
an apparent architectural tidiness.

## Decision

Silver-to-gold runs on Apache Spark, or on warehouse-native SQL where the deployment has a
warehouse. Neither is built in Phase 1: the Phase 1 graph projection reads silver directly.

## Consequences

- The `ProjectionWriter` contract (§4.6) must not assume a Spark `DataFrame` or any other
  engine-specific type, or adding Spark later becomes a rewrite of every projection.
- Deferring this keeps Phase 1 free of the Hadoop and Spark dependency trees, which matters
  disproportionately for air-gapped packaging size.

**Revisit when:** the warehouse projection is scheduled (Phase 3), or a graph rebuild over
realistic volumes proves too slow reading silver directly.
