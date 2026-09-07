# 0025 — A tenant is an agency, not a deployment, and tenants federate

**Status:** Accepted — resolves spec §10.3
**Date:** 2026-09-07
**Supersedes the open question in:** [ADR 0016](0016-single-tenant-phase1.md)

## Context

§10.3 asked whether the platform is single-tenant per deployment or shared with logical isolation.
ADR 0016 proceeded on a stated assumption of one tenant per deployment, and flagged that the answer
gets more expensive with every phase.

Jeff's answer is that the question is framed wrongly. Both deployment shapes are real:

- A large city agency will want its own deployment, and has the budget to run one.
- Several small agencies in a state will want to share one, and do not.
- A state and its counties are separate agencies, at different levels of government.

And the requirement that decides the design: **they must be able to federate.** A county
identifying a person is of interest to the state and to the neighbouring county. That is the point
of the platform, not an extension of it.

## Decision

**A tenant is an agency. It is a first-class part of the model, independent of deployment topology.**

One deployment may host one tenant or many; a tenant may be a city, a county, a state, or a task
force. Nothing in the platform may assume which. In particular, nothing may assume that "this
deployment" and "this tenant" are the same thing, because for half the customers they are not.

**Identity is tenant-qualified. Cluster identifiers are seeded with the tenant.**

`ClusterId` is derived today from entity type, key tier and key value. Two agencies sharing a
deployment, both holding the same driver licence number, would derive the same identifier and their
records would merge. That is commingling of criminal justice records between agencies, arriving
silently, as a property of a hash function.

The tenant therefore participates in the seed, and a canonical identity carries its tenant. This
also makes an identity globally meaningful, which is what federation needs: a state referring to a
county's person needs an identifier that means something outside the county's own database.

**Cross-tenant identity is an assertion, never a coincidence.**

This is the part worth stating plainly. Once cluster identifiers are tenant-scoped, County A's
person and County B's person are two distinct identities even when they are the same human. Linking
them is a deliberate, attributable act, recorded as data — an association with provenance, a
timestamp, and whoever or whatever asserted it.

That is the correct behaviour and not merely a consequence. An automatic merge across jurisdictions
is the failure that ends a criminal justice product: it cannot be explained to a court, it cannot be
retracted cleanly, and nobody can say who decided it. A deliberate link can be reviewed, disputed
and withdrawn. The platform already models exactly this well — a first-class association with
provenance is what `PersonIncidentAssociation` is.

Federation is therefore not a query that joins across tenants. It is an exchange of assertions
between them, which is also what NIEM is for.

## What this means in the code

| Concern | Today | Under this decision |
|---|---|---|
| `ClusterId` | `entityType + tier + value` | tenant participates in the seed |
| Canonical identity | opaque string | carries its tenant, so it means something elsewhere |
| Bronze layout | `<root>/<source>/<batch>` | `<root>/<tenant>/<source>/<batch>` |
| Silver tables | one `canonical` namespace | namespace per tenant |
| Graph | `MERGE` on `canonicalId` | identity includes the tenant, so two tenants cannot merge |
| Cross-tenant links | — | explicit assertions, with provenance |

## Consequences

- **This is done now, not later.** ADR 0016 named cluster identifiers as the sharpest edge, and it
  was right: retrofitting a tenant into them means re-resolving every cluster the platform has ever
  assigned, against history it has already published.
- A deployment hosting one tenant still declares that tenant. A default of "the tenant is implied"
  is how a shared deployment ends up commingled the first time somebody adds a second agency.
- Isolation between tenants sharing a deployment is logical, and therefore has to be enforced and
  tested rather than assumed. A tenant boundary that exists only in a path string is not a boundary.
- Federation between tenants needs an exchange mechanism and a trust model. Neither is in scope
  here; this decision is about making them possible without a migration.
- **Still open:** whether a shared deployment is permitted for agencies whose data classifications
  differ. That is a policy question about a specific customer, not an architecture question, and
  answering it in advance would be guessing.
