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

## Authoring a data flow

A mapping is data, not code: it is a versioned YAML artifact a domain steward edits and redeploys
without a platform build (spec §5). Author one against the module's real contracts:

```bash
./gradlew :tools:cli:installDist
./tools/cli/build/install/niem/bin/niem author --module modules/law-enforcement/src/main/resources
```

That serves the authoring surface on `http://localhost:8088` — loopback only, since Phase 1 has no
authentication beyond a stub (§8).

The mapping is drawn twice. The strip along the top is the whole mapping: source, each step, and the
canonical record each one emits. Clicking a step opens it on the canvas below as the field flow it
is — source columns in, transforms, canonical fields out, and what decides the record's identity.
Selecting a transform there shows what it writes, what it reads, and its settings; those are edited
in the panel, inputs are wired by dragging between the dots on each box, and steps are added from
the rail. **Authoring a mapping does not require reading or writing YAML.** The source is still
there, folded away at the bottom, because someone will eventually want it.

Four things about it are deliberate:

- **It does not carry its own validators.** Every check comes from the loaders the runtime itself
  uses (ADR 0021), so a mapping the editor accepts is a mapping the pipeline will load. A second
  validator would eventually disagree with the first, and the disagreement would surface at deploy.
- **The canvas is drawn in single-assignment form.** Steps assign in order and a later step may read
  what an earlier one wrote, so a target written twice becomes a chain of nodes rather than one node
  pointing at itself. Without that the picture would contain a cycle, which is both false and
  impossible to lay out.
- **Edits patch the text; they never regenerate it.** Changing a step's transform rewrites the
  one line that names it. Rewriting the document from the parsed model would be less code and would
  delete every comment in the file — and the comments are where the reasoning behind each step
  lives, the most expensive thing in a mapping to reconstruct.
- **Saving writes a new version.** The file you opened is left exactly as it was. Mappings are
  versioned artifacts (§7) and changes must be attributable (§4.8), so an in-place edit would
  quietly rewrite something an auditor may already have signed off.

## Layout

```
core/          canonical model, hop contracts, lineage
runtime/       Flink job graph construction, transformation primitives, replay
connectors/    SourceConnector SPI; file drop connector (Phase 1)
identity/      ResolutionProvider SPI and the bundled default resolver
projections/   ProjectionWriter SPI; Neo4j graph writer (Phase 1)
modules/       domain modules, law enforcement first
governance/    catalogue, policy (Phase 2)
tools/cli/     operator CLI: validate, run, replay, inspect, author
control-plane/ mapping authoring surface (ADR 0021)
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
