# 0036 — What a search document is

**Status:** Accepted
**Date:** 2026-09-29
**Context:** §4.6 (projections), §4.7 (divergence), ADR 0007, ADR 0010, ADR 0015, ADR 0026, ADR 0035
**Implements:** `projections:search`

## Context

ADR 0007 pinned Elasticsearch for search and deferred it to Phase 2, noting that it would need its
status changed and a document-shape decision when it started. This is that decision.

Search is what an investigator opens first: a name, a number, a street. The graph answers "who is
connected to whom" and the ODS answers "what is the current state"; search answers "where is the
thing I half-remember". It needs full text, and it needs to show enough about a hit to decide whether
to open it.

## Decision

**One index per canonical type, reached through an alias.** The alias is
`{prefix}-{tenant}-{type}`, e.g. `niem-us.fl.leon-so-person-incident-association`. Writers and readers
only ever use the alias; the backing index carries a timestamp. The tenant is in the name because a
cluster is claimed for one (ADR 0026) through a `{prefix}-deployment` claim document, created with
`op_type=create` so two writers cannot both claim it.

**A document is the canonical record.** Each field is mapped from its canonical type:
`string` → `text` with a `keyword` subfield, `code`/`ref`/`identity` → `keyword`, `date`/`dateTime` →
`date`, `integer` → `long`, `decimal` → `double`, `boolean` → `boolean`. All string fields are copied
into one `searchText` field so a single search box works. Lineage — run, source, mapping, time — is
under `niem`.

`decimal` → `double` is a compromise: search needs range queries, and `double` loses precision. The
ODS holds the exact value, and search is not where an amount is read.

**Mappings are explicit and `strict`.** A field the model does not declare is refused rather than
given a guessed mapping — ADR 0010's reason, in Elasticsearch's terms. A new field is added to the
mapping on open; a changed field type is rejected by Elasticsearch and reported as an error.

**Associations are documents too, and are also written onto their endpoints.** Each entity document
carries a nested `links` list with one entry per association: the association type and identity, the
role this entity plays, the other end's type and identity, and the association's own fields (stored,
not indexed). So "incidents with a suspect" is one query, and a person hit can show how many
incidents they are on without a second round trip. Each link is written by a script that removes any
entry with the same association, role and other end before adding it, so re-applying is idempotent
and an association with more than two roles keeps one link per other end. `searchText`, `links` and
`niem` are reserved: a canonical model that declares a field by one of those names is refused when the
projection opens.

**Names are not copied across links.** An incident document does not carry its people's names. Doing
so would mean re-writing every incident a person is on whenever the person changes, and a copy that
fell behind would be a search that confidently finds the wrong thing. Search finds the entity; the
graph and the ODS answer what it is connected to.

**Merging and refusal match the graph.** Entities are upserted with `doc_as_upsert`, so absent
fields are not erased — the graph's `SET n += props`. A link update carries no upsert, so an
association whose endpoint is not indexed fails rather than creating a stub document.

**A bulk request that partly failed is a failed apply.** Elasticsearch answers `200 OK` to a bulk
request in which items failed, and reports them in the body. Every response is inspected, and any
failed item fails the apply with the first few item errors in the message. A search projection that
dropped records while reporting success is exactly what this platform exists to prevent.

**A rebuild builds beside, then swaps.** New backing indices are loaded in full, every alias is
swapped to them in one atomic `_aliases` call, and the old indices are deleted. Readers never see an
empty or half-built index, and a failed rebuild leaves the previous indices serving.

**No client library.** The projection speaks the REST API through the JDK's `HttpClient` and the
Jackson already on the classpath. The official client pins a server major version and brings its own
JSON stack, and every jar it names must be mirrored for an air-gapped install (§6). The API used here
— bulk, mappings, aliases, count — is stable across 8.x and 9.x.

## Consequences

- Denormalised display fields (a person's name on an incident hit) are the next thing investigators
  will ask for. When they are added, they need a staleness story, not just a copy.
- `refresh=wait_for` is the demo default so a record is searchable when `run` returns; a large
  backfill should set `refresh: "false"`.
- ADR 0007 moves from Deferred to Accepted.

**Revisit when:** OpenSearch is required instead (the REST surface is close but aliases and scripts
differ in detail), or when denormalised fields are needed.
