# 0018. Storage zones live in their own module

**Status:** Accepted · deviation from the repository layout in spec §3

## Context

Spec §3 gives bronze and silver no module. Landing is described under §4.4 and canonical storage
is pinned in §2, but neither has a home in the tree.

Placing bronze under `connectors/` would make storage a connector concern, which it is not --
replay reads bronze with no connector involved. Placing it under `runtime/` would force
`projections/` to depend on `runtime/` in order to read silver, which inverts the intended
direction of the dependency.

## Decision

Add `storage/`, holding the zone interfaces and their backends:

- `gov.niemplatform.storage.api` — `RawEnvelope`, `BronzeStore`, and the canonical store
  interface, with no backend types in sight;
- `gov.niemplatform.storage.parquet` — the Parquet implementation of bronze.

One module rather than an api/impl pair, because Phase 1 has exactly one backend and package
separation already keeps the interfaces clean. Splitting is cheap if a second backend arrives.

## Consequences

- The §3 layout in `docs/spec.md` is further out of date. Recorded here rather than by editing
  the spec, which is an input, not a working document.
- Nothing above `storage.api` may reference Parquet, Avro, or Hadoop types. That is what keeps
  the ADR 0005 silver decision genuinely open rather than quietly foreclosed.
- Both `connectors/` and `runtime/replay` depend on this module, which is the right shape: they
  are a producer and a consumer of the same zone, not owners of it.
