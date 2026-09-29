# 0037 — A pipeline is an artifact, and the designer draws it

**Status:** Accepted
**Date:** 2026-09-29
**Context:** §0.5 (everything is a versioned artifact), §4.3, §4.6, §4.8, ADR 0010, ADR 0015, ADR 0020, ADR 0021, ADR 0022, ADR 0024, ADR 0026, ADR 0029, ADR 0034, ADR 0035
**Implements:** `core:settings`, `pipeline`, `settings()` on the connector, projection and exchange SPIs, `niem run --pipeline --artifacts`, pipeline checks in `niem validate`, the Pipelines view in the authoring surface

## Context

Every part of an ingest was already an artifact — a source definition (ADR 0029), a mapping, a
projection definition (ADR 0035), an exchange (ADR 0034) — and nothing said which of them made up a
pipeline. That lived in whoever typed the `niem run` command line, or in a shell script. The
authoring surface showed the middle of the flow — the mapping — and nothing before or after it:
there was no way to add a source, no palette, and no picture of where records went. An author coming
from StreamSets looked for the palette and the canvas and found a field editor.

## Decision

### 1. A pipeline is a file that names its parts

```yaml
pipeline: leon-cad-live
version: "1.0.0"
origin:
  source: leon-so-cad
  instance: leon-cad-kafka-1
processors:
  mapping: leon-cad-to-canonical@1.0.0
destinations:
  projections: [demo-ods, demo-search, demo-graph]
  exchanges: [fdle-cch-incidents-leon]
```

In a module's `pipelines/` directory, versioned, strict on keys (ADR 0010). It **names** its parts;
it never copies them. A pipeline that embedded a source definition would be a second copy of it that
could disagree with the first.

**The processors are the mapping.** Its contract gates and identity resolution are part of it and
versioned with it. There is nowhere in a pipeline to declare a second set.

**The mapping is pinned** (`name@version`). An unpinned mapping would change what the pipeline does
with no new version of the pipeline to say so. A projection or exchange may be named bare only when
exactly one version of it exists; otherwise the resolver asks for `@version`, for the same reason.

### 2. Where a deployment says *where*

`ArtifactCatalog` finds artifacts by what they declare themselves to be, in two tiers: the module's
own directories, and directories a deployment supplies (`niem run --artifacts`, `niem author
--artifacts`). A name found in a deployment directory shadows the module's. That is how one
pipeline runs unchanged against a laptop's stores and an agency's: the pipeline says `demo-ods`,
the deployment's own `demo-ods` says which database.

`PipelineResolver` resolves names and checks what `niem run` already checks when handed the same
files by flag: a source definition for the mapping's source, an exchange that sends that source, and
a transport, projection and wire format this deployment carries. Two strictnesses:

- **Run.** Every name resolves, or the run refuses. A projection that could not be located and was
  skipped would leave that store behind with nothing reporting it.
- **Authoring.** A projection or exchange the module does not ship is a note ("a deployment supplies
  it"), because naming a deployment's database is exactly what a module cannot do.

`niem run --pipeline` resolves to exactly the `--source/--mapping/--projection/--exchange` it stands
for, and refuses to be mixed with them. A run submits through one exchange; a pipeline naming two is
refused.

### 3. Components describe their own settings

`SettingDescriptor` (in `core:settings`) is how a connector, projection factory or exchange writer
declares the settings it reads: key, label, kind, required, default, options, and how it relates to
ADR 0015:

| Sensitivity | Meaning | In an artifact |
|---|---|---|
| `PLAIN` | an ordinary value | written |
| `ENV_VAR_NAME` | the name of the variable holding a secret (`passwordEnv`, `tokenEnv`) | written |
| `SECRET_VALUE` | the component reads the secret itself from this setting | **never written** |

Each shipped implementation has a test asserting its descriptors name exactly the keys it accepts,
so a form built from them cannot drift from `configure()`. The designer builds its forms from these;
a component that describes nothing gets no form, and its own `configure()` stays the only authority.

`SECRET_VALUE` exists because three settings still carry a literal secret: SFTP `password` and
`privateKeyPassphrase`, FTPS `password`, and Kafka `saslJaasConfig`. The designer refuses to write
them. A pipeline over such a source is completed by the deployment's own source definition, supplied
through `--artifacts`. Moving those connectors to environment-variable names is the right fix and is
not done here.

### 4. The designer

A Pipelines view in the authoring surface, the arrangement StreamSets made familiar:

- **Palette**: origins from the connector registry, the mapping, destinations from the projection
  and exchange registries — each as it describes itself, so a jar a deployment adds appears without
  anyone editing the UI.
- **Canvas**: stages placed by drag or by keyboard, wired output port to input port. The one rule a
  canvas owns is enforced as the line is drawn: origin → mapping → destinations, and a wire between
  a source and a mapping or exchange written for another source is refused with the reason. Layout
  is computed (ADR 0022); a pipeline file holds no coordinates.
- **Panel**: the selected stage's settings, generated from its descriptors; a *Test connection* for
  origins; the mapping opens in the existing field editor.
- **Add a data source**: the obvious path — transport, settings, test, sample, mapping,
  destinations, name — writing into the same draft the canvas edits, so closing it keeps the work.
- **Validate** asks the server, which stages anything unsaved into a scratch directory and resolves
  it with the same resolver `niem run` uses (ADR 0021, extended to pipelines). **Save** writes new
  files only — a new source instance, a new projection version, a new pipeline version — never over
  an existing one, and re-checks at the moment of writing.

### 5. Preview reads, and never acknowledges

Preview pulls a handful of real records (at most 50) from the origin and shows what every stage
makes of them: read, held at the contract gate and why (shapes, never values — ADR 0015), mapped to
canonical, and what each destination would receive (records for a projection, assembled documents
for an exchange), with the completeness equation.

The origin is often the one a live ingest is reading, so preview reads the way nothing else in the
platform does:

- **A Kafka origin is read on a consumer group that exists for that one preview**
  (`niem-designer-preview-…`), never the source's own group, for `maxRecords` records.
- **`SourceHandle.acknowledge()` is never called.** No offset is committed, no remote file is
  archived or deleted, no watermark moves. The pull connectors do all three only in `acknowledge()`.
- **A file drop is read in place**, as it always is.
- **Nothing is written**: no bronze, no silver, no projection, no exchange, no quarantine file.
  Records are mapped in memory with a fresh cluster index.

*Test connection* probes the same way, on its own group.

## Consequences

- A pipeline saved in the designer is the pipeline `niem run --pipeline` runs, and `niem validate`
  checks every pipeline a module ships.
- The designer never opens a projection. Opening one connects to the store and claims it for a
  tenant (ADR 0026), which a form must not do; a new projection is checked against its descriptors.
- Exchanges are chosen, not authored, in the designer. An exchange's assembly is its own artifact
  (ADR 0034) and has no form yet.
- A mapping for a new source is still written in the field editor. The designer points there.
- The authoring surface stays loopback-only (ADR 0024). Its write and preview endpoints accept only
  JSON POSTs, which a page in the same browser cannot send to localhost without a preflight this
  server never answers. The older `/api/save` does not yet make that check.
- Run and monitor from the designer are not built. The ODS run ledger (ADR 0035 §5) is where they
  will read from.

**Revisit when:** secrets move out of connector settings (and `SECRET_VALUE` can go), or a pipeline
needs more than one origin.
