# 0035 — PostgreSQL is the operational data store, and a projection is a file

**Status:** Accepted
**Date:** 2026-09-29
**Context:** §2 (pinned stack), §4.6 (projections), §4.7 (divergence), §8 (phase plan), ADR 0006, ADR 0007, ADR 0008, ADR 0026, ADR 0029, ADR 0034
**Implements:** `projections:ods`, `ProjectionDefinition` / `ProjectionFactory` / `ProjectionRegistry` in `projections:api`, `--projection` on `run` and `replay`
**Brings forward:** part of ADR 0008, by Jeff's direction on 2026-09-29

## Context

Two things were missing, and the second was hiding the first.

**There was nowhere relational to put the data.** Canonical records reach silver (Iceberg), the
graph (Neo4j), and — once ADR 0007 is built — a search index. None of those is a place where an
analyst writes SQL, where a report is joined against a reference table, or where operational work on
the records can be kept: a review queue, a steward's decision that two people are not the same
person, a note that an incident needs a second look. That work is the next thing asked of the data,
and it needs a transactional store with constraints, not an analytic table format or a graph.

ADR 0008 put a relational projection in Phase 3, as a star-schema warehouse for BI. That remains the
right answer for *analytics*. It was never the right answer for *operations*, which want current
state, normalised, with integrity enforced — an operational data store.

**`run` could not project into anything.** When ADR 0034 replaced `projections:cch` with an exchange,
the ingest's projection fan-out was left in place with nothing to open: `openProjections` returned an
empty list. The only way into gold was `replay --neo4j-uri`, which is suspended in the chart. A second
and third store would each have needed their own flags on each command that writes gold.

## Decision

### 1. PostgreSQL is the ODS

PostgreSQL is the platform's operational data store. It holds two kinds of thing, and the difference
between them is the decision:

| Schema | Holds | Owner | On `rebuild` |
|---|---|---|---|
| `canonical` (configurable) | Canonical current state: one table per canonical type | This projection | Replaced from silver |
| anything else | Operational state: workflow, review, stewardship | Whatever feature writes it | **Never touched** |

The `canonical` schema is **gold**. It is a projection of silver like the graph is, it is rebuilt
from silver on replay, and nothing may be written into it except by the projection.

Operational state is **not gold**. It is a system of record for work that happened *on* the data,
and it exists nowhere else — silver cannot rebuild a steward's decision. It lives in its own schemas
in the same database, so it can be joined against canonical state in one query, and it is backed up
as a system of record rather than regenerated as a projection.

**The invariant that keeps them apart:** a rebuild truncates the tables of the types it is
rebuilding — the types in the snapshot, which is what one mapping produces, as the graph and silver
scope a rebuild — in one statement, without `CASCADE`. If operational tables hold a foreign key into
canonical state, the truncate fails and the rebuild is refused — loudly — rather than wiping the operational state along
with the projection. An operator who genuinely intends to rebuild under live references has to
decide what those references should mean afterwards, and that is not a decision a replay should make
for them.

### 2. The projection's shape

One table per canonical type, named in snake_case (`PersonIncidentAssociation` →
`person_incident_association`). `canonical_id` is the primary key. Each association role is a
`<role>_id` column with a foreign key to its target, `DEFERRABLE INITIALLY DEFERRED`, so order within
a change set does not matter and a missing endpoint fails the commit — the same refusal the graph
writer makes. Field types follow the canonical type (`dateTime` → `timestamptz`, `decimal` →
`numeric`, repeated → array). Every column carries its NIEM provenance or extension justification as
a `COMMENT`, so an analyst in `psql` can see what a column means without this repository.

The whole model's tables exist from the first open, whatever a given mapping produces, so a
foreign key always has a table to point at. Columns are nullable: contracts own validation, and
`NOT NULL` would make adding a required field to a populated table impossible. A new field is an `ADD COLUMN`; a field whose type changed is refused,
never silently altered.

Every row records the run, source and mapping that last wrote it. `niem_meta.projection_run` is an
append-only ledger of every apply and rebuild.

Upserts merge: a value absent from a later record does not erase one already held. That is what the
graph writer's `SET n += props` does, and two projections that disagreed about the same record would
be exactly the divergence §4.7 exists to detect.

### 3. The store claims its tenant

As bronze does (ADR 0026): `niem_meta.deployment` records the tenant the first time the ODS is
opened, and a different tenant is refused on open. A database with canonical rows and no claim is
refused rather than adopted. The graph now makes the same claim when it is opened through a
projection definition, which it did not before.

### 4. A projection is a file

```yaml
projection: demo-ods
version: "1.0.0"
type: ods
settings:
  jdbcUrl: jdbc:postgresql://localhost:15432/niem
  user: niem
  passwordEnv: NIEM_ODS_PASSWORD
```

`run --projection` and `replay --projection` take any number of these. The `type` resolves through
`ProjectionRegistry`, which discovers `ProjectionFactory` implementations by `ServiceLoader` — the
same mechanism as connectors (§4.3) and exchange writers (ADR 0034). `ods`, `search` and `graph` ship
with the platform; an agency may ship another as a jar.

A definition says **where**, never **what**. There is no way to select types or rename fields,
because a projection is all of silver in another shape, and one that could be told to differ would be
a divergence by configuration.

Every projection is opened, connected and tenant-checked before `run` lands anything, and before
`replay` drops silver. If any cannot be opened, none are used.

The chart renders one definition per enabled store (`projections.ods`, `.search`, `.graph`) into a
ConfigMap and passes the same `--projection` list to the ingest CronJob and the replay Job, so the two
cannot disagree about which stores exist. `storage.graph` is gone from the chart's values.
`replay --neo4j-uri` still works for an operator at a terminal, and does not claim a tenant.

## Consequences

- **The ODS is not the source of truth for canonical data.** Silver is. Anything that reads
  `canonical.*` reads a projection, and should expect it to be replaced by a rebuild.
- **Operational features must reference canonical rows by `canonical_id`, and may use a real foreign
  key.** Doing so is what makes a careless rebuild impossible. It also means rebuilding an ODS with
  live operational state is a planned operation, not a routine one — which is correct.
- **ADR 0008 is narrowed, not superseded.** A star-schema warehouse for BI and ML is still Phase 3 and
  still `ProjectionType.WAREHOUSE`. The ODS is `ProjectionType.ODS`.
- **Phase rule 1 is set aside for this one item**, by the product owner's direction. The ODS is in
  Phase 2; nothing else from Phase 3 comes with it.
- One JDBC connection per writer and no pool: a writer is one connection for one run.
- **Not built yet:** any operational feature. This ADR decides where one goes and what protects it; it
  does not invent a workflow ahead of the requirement for one (spec §0.4).

**Revisit when:** a deployment needs the ODS on a database other than PostgreSQL, or operational state
needs to survive a rebuild that changes canonical identities.
