# 0023 — AI-assisted authoring runs behind an SPI, never on record values

**Status:** Accepted
**Date:** 2026-09-07
**Context:** §6 (air-gapped delivery is mandatory), §8 (Phase 2), ADR 0015 (records redact values), ADR 0021

## Context

Authoring a mapping is mostly a matching problem a machine is good at: this source sends
`NAME_FULL`, the canonical `Person` wants `surName` and `givenName`, and somebody has to work out
that a comma split sits between them. §8 puts AI-assisted mapping authoring in Phase 2, and Jeff has
asked for it.

Two things make it harder here than it looks.

**§6 is not negotiable.** Air-gapped delivery is mandatory, and the spec says outright that a feature
which cannot work air-gapped is designed wrong. A feature that calls a vendor cloud does not exist
for a large share of the customers this platform is built for.

**This is criminal justice data.** Anything an advisor is shown is data leaving the pipeline for
somewhere else. ADR 0015 already establishes that record values are not printed, not logged, and not
carried in events; an advisor is not an exemption from that.

## Decision

**1. Advisors are an SPI, and the bundled one needs no model at all.**

`MappingAdvisor` is an interface. The platform ships `DeterministicAdvisor`, which proposes mappings
from name similarity, type compatibility, NIEM provenance and observed value shapes. It has no model,
no network, and no configuration, so the feature is present in every deployment including a fully
air-gapped one.

An agency that wants more configures an OpenAI-compatible endpoint **they** control — a local
inference server, or their own cloud tenancy. The platform ships the seam, not the model, and not a
default destination. There is no endpoint unless somebody sets one.

**2. An advisor is shown schema, and shapes. Never values.**

The context an advisor receives carries column names, canonical field names and their NIEM types,
contract expectations, and `ValueShape` — the redacted shape the platform already computes for
observability: `###-##-####`, not the number. Raw record values are never passed to an advisor, by
any implementation, including the local one.

This is enough for the work. Knowing that a column is always eight digits with two slashes is what
picks `parseDate` and its pattern; knowing whose date of birth it is adds nothing.

**3. An advisor proposes. It never writes.**

Every suggestion is applied by a person, through the same edit path a hand-made change uses, and is
validated by the same loaders. There is no path from an advisor to disk that does not pass through
somebody accepting it. A mapping is a reviewed artifact (§4.8) and a machine cannot be the reviewer.

## Why not the alternatives

**Bundle model weights in the package.** Self-contained and identical everywhere, but adds gigabytes
to every release, imposes GPU requirements on hardware agencies already own, and pins the platform to
one model's quality for the life of the deployment.

**Vendor cloud only.** Best suggestions and least work, and it makes the feature nonexistent for
air-gapped customers, which §6 calls designed wrong. It also means two products.

**Deterministic only, no seam.** Ships fastest and is fully explainable, but forecloses something
Jeff has asked for, and the seam costs almost nothing to define now and a great deal to retrofit.

**Sampled real values.** Materially better suggestions. Also criminal justice record values reaching
a model, which needs its own decision, its own agency-level opt-in, and is not something to acquire
as a side effect of an authoring convenience.

## Consequences

- The deterministic advisor is the floor, not a stopgap. It must be good enough to be worth using on
  its own, because for some deployments it is the only one that will ever run.
- Every advisor implementation is bound by the no-values rule. The context type is the enforcement
  point: if a value cannot be put in it, no implementation can leak one.
- Suggestions carry their reasoning. An author has to be able to see why a mapping was proposed
  before accepting it into an artifact that will be audited.
- If sampled values are ever wanted, that is a new ADR and a new opt-in, not a configuration flag.
