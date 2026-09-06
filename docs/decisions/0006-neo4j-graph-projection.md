# 0006. Neo4j for the Phase 1 graph projection

**Status:** Accepted · pinned by spec §2 and §4.6

## Context

NIEM's association structures are already graph edges. A projection that flattened them into
join tables would discard the shape the canonical model went to trouble to preserve.

## Decision

Neo4j is the Phase 1 graph projection, written through the `ProjectionWriter` interface
(§4.6) so it is swappable. Canonical entities become nodes labelled by canonical type name;
canonical associations become edges, with each association role becoming an endpoint.

Integration tests run against a real Neo4j via testcontainers, not a mock. A graph writer
tested only against a fake proves nothing about Cypher correctness or constraint behaviour.

## Consequences

- No Neo4j type may appear above the `ProjectionWriter` interface. "Abstracted behind a writer
  interface so it is swappable" is only true if nothing leaks.
- `rebuild` must be able to reconstruct the entire graph from silver, which means the writer
  cannot depend on incremental state it accumulated earlier.
- Neo4j licensing is a commercial consideration for air-gapped delivery; it does not affect
  the code, but it is the most likely reason this ADR is superseded.
