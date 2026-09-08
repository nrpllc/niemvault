# 0026 — One tenant per deployment, enforced rather than promised

**Status:** Accepted
**Date:** 2026-09-07
**Narrows:** [ADR 0025](0025-tenancy-and-federation.md)
**Context:** §6 (deployment), §4.8 (governance), §10.3

## Context

ADR 0025 established that a tenant is an agency and that tenants federate, and left both deployment
shapes open: an agency may have its own deployment, or several may share one with logical isolation.

Jeff's direction is to optimise for simplicity and legal safety, and to make minimised risk a
property of the product rather than a caveat in the documentation.

Those two goals point at the same answer, and it is a subtraction.

## Decision

**A deployment serves exactly one tenant. Shared logical multi-tenancy is not built and not
supported.**

A deployment records the tenant it serves the first time it stores anything, and refuses data
belonging to anyone else from then on. Loudly, at the point of writing, not as a filter applied at
the point of reading.

Several agencies may still share infrastructure and cost — by running several deployments, operated
by one party. What they do not share is a database, a bucket, a graph, or a process.

## Why this is the safer answer and not merely the simpler one

**It deletes a vulnerability class instead of defending against one.** Logical isolation is only as
good as every read path that has to remember to filter by tenant. That is a check that must be
correct in every query, in every projection, in every new feature, forever — and one omission is an
unauthorised disclosure between agencies. There is no such check here, because there is nothing to
filter: a deployment holds one agency's data and that is all it can hold.

**It is a claim an agency's counsel can verify in a sentence.** "Your records are in your own
database" is checkable. "Your records are in a shared table with a tenant column, and every query
filters correctly" is a promise about code that a lawyer cannot audit and a court will not take on
faith.

**It keeps the authority where the law puts it.** Sealed records, juvenile records, CJIS-restricted
fields — the rules differ per agency and per record. Pooling forces every agency's legal regime into
one policy engine, and the first wrong entry is an unauthorised disclosure made on someone else's
behalf. Physical separation leaves each agency's authority with the agency.

**Revocation becomes real.** An agency that stops sharing stops at its own end. It does not depend on
another party deleting rows.

## The cost, stated honestly

**Operational security gets harder, not easier, if agencies self-host.** Ten small agencies running
ten deployments means ten patch cadences, and the unpatched one is the actual attack vector — far
more than architectural leakage ever is. This decision is therefore only safe in combination with
managed operation: the answer for an agency that cannot run infrastructure is a deployment we
operate for them, not a slice of somebody else's database. §6 already requires that both delivery
modes exist.

**Failure to connect is a harm too.** An investigator who never learns the person in front of them is
wanted next door has been failed by the system, and that is the harm NIEM exists to address. Physical
isolation raises the cost of connecting, so the platform must lower it deliberately: federation has
to be good, cheap, and routine, or this decision trades a disclosure risk for an investigative one.

## The properties this commits us to

These are the claims the product makes. Each is testable, and none is a matter of configuration.

1. **No ambient access.** Nothing is visible across an agency boundary because it happens to sit in
   the same database, bucket, or network. There is no cross-tenant read path to secure because there
   is no cross-tenant read path.
2. **A store refuses data that is not its tenant's.** Enforced when written, not filtered when read.
3. **Every cross-agency disclosure is a record.** Who asked, what was returned, when, and under whose
   authority — the artifact handed to counsel, an auditor, or a court.
4. **Absence is reported.** A federated answer says which participants did not respond. A federation
   that quietly under-answers teaches investigators that a clean result means nothing was found,
   which recreates the failure-to-connect harm while looking healthy.

## Consequences

- The tenant-scoped cluster identifiers from ADR 0025 stay, and stay load-bearing. They are what make
  an identity meaningful outside the agency that minted it, which is what federation needs, and they
  make an accidental cross-agency merge impossible even if two deployments were ever consolidated.
- No policy engine, no row-level security, no tenant column, no per-read authorisation across
  agencies. None of it is built, and building it later would be reversing this decision rather than
  extending it.
- Properties 3 and 4 are not yet built. They are commitments recorded here so that federation, when
  it is built, is built to them rather than retrofitted with them.
- **Superseded if** a customer requires several agencies in one database. That is a reversal, not a
  configuration change, and it should be recognised as one.
