# 0007. Elasticsearch for the search projection

**Status:** Deferred · pinned by spec §2, scheduled for Phase 2

## Context

Search is what an investigator actually opens first, so it matters commercially more than its
Phase 2 position suggests. It is nonetheless second architecturally: the graph projection is
what proves the canonical association model, and search can be rebuilt from silver at any time.

## Decision

Elasticsearch is the search projection, built in Phase 2 behind the same `ProjectionWriter`
interface as the graph projection. Not built in Phase 1.

## Consequences

- The `ProjectionWriter` contract is designed now against two known consumers, graph and
  search, rather than being generalised later from a single implementation.
- Full-rebuild-from-silver must hold for search too, which constrains what silver has to
  retain.

**Revisit when:** Phase 2 begins.
