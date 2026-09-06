# NIEM Integration Platform

A canonical data core derived from NIEM, a mapping layer that turns any source into it, and
governance, lineage, and observability built in rather than integrated.

Government agencies spend disproportionately on point-to-point integration — agency to agency,
local to state to federal. Each integration is bespoke, undocumented, and breaks silently. This
platform makes that integration a solved, licensed capability, with the mappings themselves as
the reusable, compounding asset.

The failure this platform exists to prevent is **silent corruption, not visible crashes**. A
source that quietly changes format while nothing errors does more damage than a pipeline that
falls over. Most of the design follows from that.

> **Status: Phase 1 in progress.** The vertical slice is being built. See
> [Phase 1 progress](CLAUDE.md#phase-1-progress) for what is and is not done.

---

## Reading order

| Document | What it covers |
|---|---|
| [`docs/spec.md`](docs/spec.md) | The build specification. Component contracts, execution model, phase plan, acceptance criteria. |
| [`docs/decisions/`](docs/decisions/README.md) | One ADR per pinned decision and per decision taken during implementation. |
| [`CLAUDE.md`](CLAUDE.md) | Working notes: environment setup, gotchas, open questions, progress. |

## Building

Requires a JDK 21 on `JAVA_HOME`. Nothing else — the wrapper fetches Gradle and verifies its
distribution checksum.

```bash
./gradlew build                 # compile, generate, and test everything
./gradlew canonicalModel        # validate and regenerate every canonical model
```

Integration tests use testcontainers and need a working Docker daemon.

## Layout

```
core/          canonical model, hop contracts, lineage
runtime/       Flink job graph construction, transformation primitives, replay
connectors/    SourceConnector SPI; file drop connector (Phase 1)
identity/      ResolutionProvider SPI and the bundled default resolver
projections/   ProjectionWriter SPI; Neo4j graph writer (Phase 1)
modules/       domain modules, law enforcement first
governance/    catalogue, policy (Phase 2)
tools/cli/     operator CLI: validate, run, replay, inspect
deploy/        Helm chart and manifests
build-logic/   convention plugins and the canonical model code generator
```

Package root is `gov.niemplatform`.

## Two things to know before contributing

**The canonical model is generated, not written.** `core/canonical/src/main/canonical/*.yaml`
is the source of truth. Every type, field, and role must declare NIEM provenance *or* an
extension with a written justification — never neither, never both — and extensions are
namespace-segregated from NIEM-sourced types in both directions. The build enforces all of it,
and unrecognised keys are errors rather than warnings.

**NIEM references are currently unverified.** No NIEM 6.0 release is on disk to check them
against, so provenance is asserted rather than validated. See
[ADR 0011](docs/decisions/0011-niem-reference-verification.md) — this must be resolved before
Phase 1 can be signed off.

## Delivery

One artifact, two modes: managed updates pushed from a vendor cloud, and an installable
air-gapped package. Many criminal justice environments will not permit outbound connectivity,
so air-gapped is mandatory, not a fallback. There is no separate on-prem codebase and no
on-prem-only code path — a feature that cannot work air-gapped is designed wrong.
