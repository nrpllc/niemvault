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

## The decisions site

`docs/site/index.html` is a readable account of the decisions and what they cost — what was made
impossible, why, and what each one prevents. It lives here rather than in a separate repository so it
is versioned alongside the ADRs it summarises and cannot drift from them.

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
- **Suggestions come from an advisor that proposes and never writes.** The bundled one needs no
  model, no network and no configuration, so the feature exists in an air-gapped deployment. Point
  `--bronze` at what has landed and it reads the *shape* of each column — `##/##/####`, never the
  date — which is what lets it pick `parseDate` **and** its pattern. Every proposal explains itself
  and is applied by a person. See [ADR 0023](docs/decisions/0023-mapping-advisor.md).
- **Contracts are edited from the canvas too.** Selecting a source column shows what the contract
  expects of it — type, required, pattern — and changes are written straight to the contract file.
  They live there rather than on a screen of their own because the question an author actually has
  is "is this the field that will quarantine my records", and that is answered by looking at the
  expectation and the flow reading it at the same time.
- **Contracts are checked against the mapping, not just named by it.** A field the contract requires
  that no step writes is drawn as an empty slot on the canvas and reported as a problem. That
  mapping loads perfectly and quarantines every record it ever sees — it is knowable from the two
  artifacts sitting next to each other, and knowable while the author still has the file open.
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

## NIEM provenance is checked, not asserted

Every type, field and role in the canonical model declares either NIEM provenance or an extension
with a written justification — and every provenance claim is resolved against a NIEM 6.0 release
manifest when the model is generated. The build fails on a citation that does not exist, and says
where the name *is* declared if it exists elsewhere.

Manifests live in `core/canonical/src/main/niem`, generated from the published schemas and committed
so an air-gapped build can verify its own citations (§6). See
[ADR 0011](docs/decisions/0011-niem-reference-verification.md) — including the three real errors this
found in content that had passed every other check.

## Isolation

A deployment serves exactly one agency, and says so. A bronze store records the tenant it belongs to
the first time it stores anything and refuses anyone else's data from then on — on open, before
anything is written, because bronze is append-only and there is no undoing an interleave.

Shared logical multi-tenancy is deliberately **not built**. There is no cross-tenant read path to
secure because there is no cross-tenant read path, and no tenant column to remember to filter by.
"Your records are in your own database" is a claim an agency's counsel can verify; "every query
filters correctly by tenant" is a promise about code that a lawyer cannot audit. See
[ADR 0026](docs/decisions/0026-isolation-by-construction.md) — including the costs, which are real:
this is only safe alongside managed operation, and it raises the price of connecting agencies, which
federation has to bring back down.

## Foreign feeds and federation

Two boundaries, deliberately not the same thing
([ADR 0027](docs/decisions/0027-foreign-feeds-and-federation.md)).

A state or federal system dictates its own protocol, schema and interaction model, and may not speak
NIEM at all. That is a mapping problem, and mapping is what this platform is for — it is never a
reason to bend the canonical model. Push, poll and live query are properties of a connector; all
three land through the same contract-gated path, because two ways into canonical means one of them is
not validated.

Federation between tenants running this platform is one interface, speaking canonical and NIEM, with
no per-partner variation. Foreign adapters absorb variation; federation does not.

A connector also declares whether its records may be **kept**. Criminal history responses frequently
may not be — the rules permit use for the purpose at hand and forbid a copy — so a `TRANSIENT` source
is refused at the point of landing rather than filtered afterwards. Its only durable trace is the
disclosure record, which is exactly what you are permitted to keep.

## The disclosure record

Every cross-agency release is recorded before it happens: who asked, under what authority, what they
actually got, what was withheld and why, and who decided. Append-only, one record per line, readable
without this software — an auditor will not have it installed.

It names records and cannot contain one. An audit log holding the data it audits is a second copy of
that data, usually with weaker access controls and longer retention, so the log of who saw what
becomes the easiest place to see it. A reviewer needs to know that two Person records went to the
state under a named statute and one was withheld as sealed; they rarely need to know who those people
are.

`DisclosureLog.disclosing` writes the record and only then produces what is released, so the ordering
cannot be got wrong. If the record cannot be written, nothing crosses the boundary.

## The catalogue

```bash
niem catalogue --module modules/law-enforcement/src/main/resources
niem catalogue --module ... --gaps-only    # exits 2 if anything is undocumented or unread
```

It is also a view in the authoring surface — a **Catalogue** tab beside the flow — where the meanings
are editable. A records manager can say what `BEAT` means without opening a mapping, and what they
write is patched into the artifact that declares the column, so the meaning travels with the thing it
describes and is reviewed alongside it.

Two halves, and only one is derivable. The structural half — sources, mappings, contracts, canonical
types, versions, NIEM provenance — is assembled from artifacts that already describe themselves.

The glossary half cannot be derived from anything. NIEM provenance answers *what standard does this
field come from*; a glossary answers *what does this agency call it, and what did they mean*. That
`BEAT` is operational districting rather than a postal boundary, or that the literal `UNK` in a
licence number is an absent value wearing the shape of a present one — that exists only in the head
of whoever wrote the mapping until they write it down. It is read from `doc:` on declared columns and
contract fields, and gaps are reported rather than skipped: a catalogue listing only its documented
terms would tell an agency their source is fully understood.

## CI

`.github/workflows/build.yml` runs `./gradlew testAll -Pdocker` — the container-backed tests
included. That is deliberate: two production defects in a row were invisible to every test that did
not touch a real object store, and were found by running the artifact by hand. It also builds the
image, validates the shipped module inside it, and renders the chart with every feature enabled,
asserting two properties the deployment depends on — that the authoring surface renders no Service or
Ingress ([ADR 0024](docs/decisions/0024-authoring-surface-is-not-exposed.md)), and that credentials
are referenced from Secrets rather than inlined.

## Deploying

One image, both delivery modes (§6) — the same artifact the vendor cloud pushes in managed mode is
what an agency loads from a tarball in an air-gapped one.

```bash
docker build -f deploy/Dockerfile -t niem-platform:dev .
bash deploy/package-airgapped.sh          # image tarball + chart + modules + checksums
helm install le deploy/helm/niem-platform --set module.configMapName=le-module
```

The image carries the platform and nothing else. Domain modules are versioned content on their own
release cycle (§7), so they are mounted rather than baked in — otherwise a platform patch would force
a content release, and the reverse.

Ingest runs as a CronJob, because Phase 1's connector is a file drop: a job that lands what arrived
and exits, not a service whose health is unrelated to whether any data moved. Replay ships as a
**suspended** Job, since it drops and rewrites every canonical table it produces and is not something
an install should perform.

The authoring surface is off by default, has no Service, and binds to loopback. That is the access
control, not an oversight: `kubectl port-forward` is the only way in, which puts the cluster's own
authentication, RBAC and audit in front of a surface that has none of its own
([ADR 0024](docs/decisions/0024-authoring-surface-is-not-exposed.md)). Credentials come from
Kubernetes Secrets and never from `values.yaml`.

## Layout

```
core/          canonical model, hop contracts, lineage
runtime/       Flink job graph construction, transformation primitives, replay
connectors/    SourceConnector SPI; file drop, Kafka, SFTP and FTPS transports
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
