# 0016. Phase 1 assumes one tenant per deployment

**Status:** Proposed · spec §10 open question 3 remains open

## Context

Spec §10 lists the multi-tenancy model as an open question affecting storage layout and policy
enforcement. Phase 1 explicitly places multi-tenancy out of scope.

Building Phase 1 requires a storage layout regardless, and a layout chosen without thinking
about tenancy is a layout that will need migrating.

## Decision

Phase 1 proceeds on the assumption of **one tenant per deployment**. This is an assumption
stated to be unblocked, not a resolution of the open question.

Storage paths are laid out as `<root>/<zone>/<source>/...` with no tenant segment. If shared
multi-tenancy is chosen later, a tenant segment is inserted above `<zone>`.

## Consequences

- Nothing in Phase 1 may assume the absence of a tenant dimension in a way that is expensive
  to reverse: no global singletons keyed by source alone, no cluster identifiers that would
  collide across tenants.
- Cluster identifiers from identity resolution are the sharpest edge here. A shared-tenancy
  model would require them to be tenant-scoped, and retrofitting that means re-resolving
  history.
- **Open, for Jeff:** single tenant per deployment, or shared with logical isolation? This
  blocks nothing in Phase 1 but gets more expensive to answer with every phase.
