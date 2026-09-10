# CLAUDE.md — agent working notes

Working context for the NIEM Integration Platform. Kept current per spec §9. If you are a
fresh session, read this, then `docs/spec.md` §8 (phase plan), then `docs/decisions/README.md`.

---

## The rules that actually bite

From `docs/spec.md` §0. These are not stylistic:

1. **Do not build ahead of the current phase.** Phase 1 acceptance criteria are the definition
   of done. Anything in Phase 2+ stays unbuilt even when it would be quick.
2. **"Decision:" is settled. "Open:" means stop and ask.** Do not resolve an open question
   unilaterally. Two are still open — see *Open questions* below.
3. **Contracts before implementation.** Write the interface and its tests first.
4. **No placeholder code.** If something cannot be built without an unresolved decision,
   surface the question rather than stubbing something plausible.
5. **Every mapping, schema, and transformation is a versioned artifact on disk.**

---

## Environment

This machine had no JVM toolchain before this project. What was installed:

- **JDK 21** at `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot` (Microsoft OpenJDK, via
  winget). `JAVA_HOME` and `PATH` were set at **user** scope — a shell started before that
  will not see them.
- **Gradle 8.12** bootstrapped into `%USERPROFILE%\.niem-tools\gradle-8.12`, used once to
  generate the wrapper. Use `./gradlew` from here on; the bootstrap copy is not needed again.

The wrapper pins the distribution checksum, so `gradlew` verifies the Gradle distribution on
every machine.

If `gradlew` reports no JDK, the shell predates the install:

```powershell
$env:JAVA_HOME = (Get-ChildItem 'C:\Program Files\Microsoft\jdk-21*' -Directory | Select-Object -First 1).FullName
```

Docker is available (needed for testcontainers integration tests, e.g. Neo4j).

---

## Build layout

| Path | What it is |
|---|---|
| `build-logic/` | Included build. Convention plugins + the canonical model code generator. |
| `gradle/libs.versions.toml` | Single source of dependency versions. No dynamic versions, no snapshots — an air-gapped mirror cannot resolve either. |
| `core/canonical/` | Canonical DSL sources, generated types, and the generic `Record`. |
| `core/observability/` | The §4.7 event taxonomy and its emitters. Depended on by `contracts`. |
| `core/contracts/` | Hop contracts, the schema validator, quarantine, and the on-disk contract loader. |
| `core/disclosure/` | The append-only record of what crossed an agency boundary (§4.8, ADR 0026). |
| `connectors/kafka/` | Phase 2. Bounded slices of a topic, landed through the same `LandingService`. |
| `control-plane/` | The mapping authoring surface. Calls the runtime's loaders; owns no validator. |
| `docs/decisions/` | One ADR per pinned decision and per decision taken during implementation. |

Convention plugins are applied explicitly per module. There is deliberately no `allprojects`
or `subprojects` block — a module's build file states what that module is.

**`niem.java-conventions`** sets Java 21, strict lint, and the `--add-opens` flags embedded
Flink needs on Java 21. Do not add those flags per module; they belong in the plugin.

**`niem.canonical-codegen`** wires `src/main/canonical/*.yaml` → generated Java records.

### Useful commands

```bash
./gradlew build                      # everything
./gradlew canonicalModel             # validate + regenerate every canonical model
./gradlew -p build-logic test        # the codegen's own tests
./gradlew :core:canonical:test
./gradlew testAll                    # every test, including the included build-logic build
./gradlew testAll -Pdocker           # also runs tests tagged "docker" (silver store)
node C:/src/zendesign/scripts/zen-test.mjs   # merge JUnit XML into .zen/ for the Zen sidecar
```

---

## The canonical model (§4.1)

`src/main/canonical/*.yaml` is the **source of truth**. Generated records under
`build/generated/sources/canonical` are a build product and are never committed.

Rules the build enforces — see `CanonicalModelValidator`:

- Every type, field, and role declares NIEM `provenance` **or** an `extension` with a written
  `justification`. Never neither, never both.
- Extension types live under the extension namespace prefix; NIEM-sourced types may not.
  Enforced both ways.
- Two canonical types may not claim the same NIEM type.
- Unrecognised keys are **errors**. A silently ignored key is how a provenance annotation goes
  missing without anyone noticing.

A NIEM-sourced type *may* carry extension **fields** — that is NIEM's augmentation pattern.
Only *types* are namespace-segregated.

### NIEM references are verified at build time

Every `niemNamespace` / `niemType` / `niemElement` is resolved against committed release manifests
under `core/canonical/src/main/niem`, generated from the published NIEM 6.0 schemas. **The codegen
task refuses to run without them** — degrading quietly to "unverified" is the failure
[ADR 0011](docs/decisions/0011-niem-reference-verification.md) exists to prevent.

Committed, not fetched: an air-gapped build has to be able to check its own citations (§6).

Verification found three real errors when first run: `nc:PersonSexCode` (right name, wrong
namespace — it is `j:PersonSexCode`), `nc:DriverLicenseIdentification` (does not exist; it is
`nc:PersonLicenseIdentification`), and `PersonIncidentAssociation` being an extension when
`nc:ActivityPersonAssociationType` fits it exactly.

**The whole NIEM 6.0 model is committed** — niem-core and all 17 published domains, 27,400 lines
and 832K, generated by `tools/niem-manifest/GenerateNiemManifests.java`. Run it with Java 21's
single-file mode (`java tools/niem-manifest/GenerateNiemManifests.java`); it takes `--domain` to do
one and `--from-dir` to read schemas already on disk. It is **deliberately not a Gradle task**: it is
the only thing in the repository that touches the network, and §6 requires the build never to. It
rewrites a manifest only when the content actually changes, so a rerun does not churn the tree.
Code lists (`codes/`, 38 of them) are excluded on purpose — they enumerate permitted values, not
citable types, and would bury the domains in the coverage browser.

---

## Gotchas already hit

- **`LibrariesForLibs` is not on the classpath in precompiled script plugins.**
  `niem.java-conventions` resolves the catalogue via `VersionCatalogsExtension` instead. Do not
  "fix" this back to `libs.foo` — it will not compile.
- **`PowerShell 5.1` + `Invoke-WebRequest` needs `-UseBasicParsing`** in non-interactive mode,
  and `.Content` may come back as `byte[]` rather than a string.
- **Large Java files via bash heredoc are fragile.** Use the Write tool. Backslashes in regexes
  get eaten; write those files with the Write tool or fix the escapes afterwards.
- **Module directory names are not unique, and Gradle coordinates come from them.**
  `connectors/api`, `identity/api`, and `projections/api` would all be `gov.niemplatform:api`, and
  Gradle silently substitutes one for another -- the symptom is a package that "does not exist".
  `niem.java-conventions` derives the group from the parent path to prevent it. Do not simplify
  that back to a flat group.
- **Testcontainers needs a version that speaks a modern Docker API.** Docker Engine 29 refuses
  API versions below 1.40; the docker-java client in Testcontainers 1.20.4 requests v1.32, so the
  engine replies HTTP 400 with an empty `/info` and Testcontainers reports "Could not find a valid
  Docker environment" on a machine where the CLI works fine. **Testcontainers 1.21.4 fixes it.**
  Do not chase Docker Desktop settings for this -- the named pipes are healthy, and the
  "Allow the default Docker socket" toggle is macOS-only. Check `docker version --format
  '{{.Server.MinAPIVersion}}'` against the `GET /vX.YY/info` line in the test log first.
- **Iceberg's S3FileIO needs `software.amazon.awssdk:sts` on the classpath** even when no role is
  assumed: `AwsProperties` touches STS model classes during construction.
- **Neo4j's driver rejects `java.time.Instant` outright.** It has no mapping for it. Store a
  zoned datetime at UTC instead: the same moment, and the property stays temporal so an
  investigator can range-query it rather than string-match a timestamp.
- **The authoring surface patches mapping text; it never round-trips YAML.** `MappingText` locates
  and rewrites individual lines. Regenerating the document from `MappingDefinition` would be far
  less code and would delete every comment in the file -- which is where the reasoning behind each
  step lives. Do not "simplify" it into a dump-and-reload. The tests assert on comment counts and
  line counts for exactly this reason.
- **The field flow is built server-side, in `FieldGraph`, in single-assignment form.** A hop's steps
  assign in order and later steps read earlier results -- `surName` is produced by a split and then
  consumed by an upper. One node per *name* would make that a cycle. Each write gets its own node
  and each read binds to the most recent earlier write. Do not "simplify" it to one node per field.
- **`.dockerignore` must not use `**/build/`.** It also matches the Java package
  `gov/niemplatform/build/`, which silently drops the canonical code generator from the build
  context; the image then fails with "plugin implementation class not found", which points nowhere
  near the cause. Gradle output is matched by position instead: `/build`, `/*/build`, `/*/*/build`.
- **The authoring surface binds to loopback, and that is the deployment's access control** --
  [ADR 0024](docs/decisions/0024-authoring-surface-is-not-exposed.md). A Service cannot reach it, so
  `kubectl port-forward` is the only route, which puts the API server's authentication, RBAC and
  audit in front of a surface that has none of its own. Do not "fix" the bind address to add a
  Service.
- **A connector declares its interaction mode and its retention posture; neither has a default** --
  [ADR 0027](docs/decisions/0027-foreign-feeds-and-federation.md). A default retention would be
  `RETAINED`, and an author who did not think about it would silently land data that may not lawfully
  be kept. `LandingService` refuses a `TRANSIENT` source before it opens it, because bronze is
  append-only and a transient record written by mistake cannot be taken back out.
- **Foreign integration and federation are different boundaries.** Foreign systems get arbitrary
  protocols, schemas and modes -- that is where the mess lives. Federation between our own tenants is
  one interface speaking canonical and NIEM, with no per-partner variation. An integration that will
  not fit federation is a foreign integration; it does not become a dialect of federation.
- **A disclosure record names records and cannot contain one.** An audit log holding the data it
  audits is a second copy with weaker access controls and longer retention -- the log of who saw what
  becomes the easiest place to see it. `DisclosureRecord` has no field that can hold a value, and a
  test asserts it by reflection. Do not add one.
- **`DisclosureLog.disclosing` writes the record before it releases anything.** If the record cannot
  be written, nothing crosses the boundary. An agency that cannot write to its own audit log has lost
  the right to release data until it can, because it can no longer say what it released.
- **A bronze store belongs to one agency and refuses another's, on open** --
  [ADR 0026](docs/decisions/0026-isolation-by-construction.md). A `.tenant` marker is written on
  first use and checked every time. Shared logical multi-tenancy is **not built and not supported**:
  there is no cross-tenant read path to secure because there is no cross-tenant read path. Do not
  add a tenant column and a filter -- that is a reversal of the decision, not an extension of it.
- **`ClusterId` is seeded with the tenant, and an index belongs to one** — [ADR 0025](docs/decisions/0025-tenancy-and-federation.md).
  Without it, two agencies sharing a deployment and holding the same licence number derive the same
  identifier and their records merge, silently. Cross-agency linking is a deliberate assertion, never
  a coincidence of hashing. `--tenant` is required on `run` and `replay` even for a single-tenant
  deployment.
- **Anything read inside the Flink pipeline factory closure must be a local.** Reading a picocli
  `@Option` field captures `this`, and `NotSerializableException: RunCommand` arrives *after* the
  data has landed. `runId`, the quarantine path and the tenant are all copied to locals for this.
- **An H2 Iceberg catalogue is write-once unless `DATABASE_TO_LOWER=TRUE`.** H2 folds unquoted
  identifiers to upper case, so Iceberg looks for `iceberg_tables`, is told it does not exist, and
  issues `CREATE TABLE` -- which fails. `IcebergCanonicalStoreConfig` appends the setting for H2
  URIs only. Without it the second `niem run` against the same catalogue fails, which every test
  missed for as long as they each used a fresh catalogue.
- **An H2 *file* catalogue is single-process.** One JVM holding it locks the file. Fine for the
  chart, whose ingest CronJob is `concurrencyPolicy: Forbid`; use PostgreSQL for anything else.
  Never set `DB_CLOSE_DELAY=-1` on a file database -- it holds the lock until the JVM exits.
- **`run` appends to silver; `replay` drops and rewrites it.** Same store, opposite obligations.
  Appending on replay would double every record it reprocessed and make criterion 6 unprovable.
- **`[hidden]` needs `!important` in this stylesheet.** `main { display: grid }` is more specific
  than the user agent's `[hidden] { display: none }`, so without it the flow view stays on screen
  behind the catalogue.
- **A textarea sized from `scrollHeight` must already be in the document.** Measuring a detached
  element returns nothing useful and silently leaves every long meaning cut off -- which is the half
  that matters, because that is where the caveats are.
- **A column may be a bare name or `{name, doc}`; a contract field may carry `doc:`.** Both forms
  stay valid -- a format that demands documentation gets documentation saying `TODO`. The docs are
  side tables (`DecoderSpec.columnDocs`, `Schema.fieldDocs`) rather than components of the records
  that carry them: validation never reads them, and threading them through would make every
  construction carry documentation nothing at run time consults.
- **An advisor never sees a record value** -- [ADR 0023](docs/decisions/0023-mapping-advisor.md).
  `MappingAdvisor.Context` is the enforcement point, not a convention: it carries names, NIEM types
  and `ValueShape`, and has no field that can hold a value. Do not add one. Any remote endpoint an
  agency configures is bound by the same type.
- **The bundled advisor is the floor, not a stopgap.** In an air-gapped deployment with no endpoint
  configured, `DeterministicAdvisor` is the only advisor that will ever run. Its synonym table is
  what makes it useful on a real feed -- `DOB` scores zero against `birthDate` on every generic
  similarity measure.
- **A contract is written in place; a mapping is versioned up.** Deliberately different. A contract
  describes what a source *actually sends* -- once the source changes, the old description is not
  something anyone wants left running. A mapping is a reviewed decision about meaning. Bumping a
  contract's own version when a change is breaking is the author's judgement, not the editor's.
- **`ContractCoverage` is the check that a mapping actually satisfies its contracts.** Contract
  *identity* (right name, right version, right hop) was already checked; field coverage was not. A
  mapping that fails to write a field the contract requires loads perfectly and then quarantines
  every record -- silently, because §4.2 requires bad data not to halt the pipeline. It lives beside
  the authoring surface, not in the mapping loader, because neither artifact is wrong alone.
- **Node coordinates are never written to a mapping** -- [ADR 0022](docs/decisions/0022-flow-layout-is-computed.md).
  Layout is recomputed; dragging is session-only.
- **The control plane carries no validators of its own** -- ADR 0020/0021. `/api/validate` and
  `/api/edit` both call `MappingLoader`, `TransformFactory`, `ContractLoader` and
  `ModuleManifestLoader`. If a check is missing from the editor, add it to the loader, never to the
  editor.
- **`scrollIntoView({behavior: "smooth"})` is silently ignored** where reduced motion is in effect,
  including some automation contexts. A jump that sometimes does not happen is worse than one that
  never animates -- the authoring UI uses instant scrolling deliberately.
- **A source position advances only after the bronze commit, never before** —
  [ADR 0028](docs/decisions/0028-streaming-sources-land-in-bounded-slices.md). `LandingService` calls
  `SourceHandle.acknowledge()` after each batch commits. Acknowledging first turns a crash into *lost*
  records -- the source will not resend them and bronze does not have them, and nothing detects it.
  Acknowledging after turns the same crash into duplicates, which envelope identity already detects.
  Do not "simplify" the acknowledgement into `close()`: closing is not a landing event, and a commit
  there would acknowledge a batch that failed on its way to bronze.
- **A Kafka handle commits what it *yielded*, not the consumer's position.** A poll returns up to 500
  records and the consumer's position jumps to the end of them immediately; the caller may have taken
  a hundred. A bare `commitSync()` acknowledges four hundred records nobody saw. `KafkaSourceHandle`
  tracks the furthest yielded offset per partition for exactly this. The everyday tests catch it
  (`MockConsumer`, no broker needed); an end-to-end test that happens to consume everything it polls
  never would.
- **The Kafka slice ceiling is checked *before* the buffer, not after.** One poll fills the buffer with
  a whole batch, so a ceiling only consulted when the buffer runs dry overshoots `maxRecords` by most
  of a poll. Caught by a test; it read as a harmless reordering.
- **`KafkaConnector.retention()` throws before `configure()`, and `retention` is a required setting.**
  Unlike a file drop, a topic's retention posture is not a property of the transport: the same broker
  carries an agency's own feed and a state system's non-retainable responses (ADR 0027). The only
  assumption that would let a run proceed is `RETAINED`, which is an unlawful retention reached by
  omission. Do not give it a default.
- **A topic is read in bounded slices, and that is deliberate.** `LandingService` drains a handle to
  completion, so the slice ends at a record ceiling or an idle window and the consumer group resumes
  next time. An unbounded landing loop would need its own commit cadence, back-pressure and shutdown
  -- three second answers to questions `LandingService` already answers for every transport.
- **`MockConsumer` refuses every call once closed**, so "closing commits nothing" is untestable
  against it directly. `KafkaSourceHandleTest` subclasses it to survive `close()`. Testcontainers'
  modern Kafka container is `org.testcontainers.kafka.KafkaContainer` with an `apache/kafka` image,
  not the deprecated Confluent one in `org.testcontainers.containers`.
- **`run` takes `--source <artifact>` for any transport; `--drop` is a file-drop shorthand** —
  [ADR 0029](docs/decisions/0029-a-source-is-an-artifact.md). Both build the same `SourceDefinition`
  and meet before anything lands, so there is one landing path rather than two that drift. The
  connector comes from `ConnectorRegistry.discover()`, never from a `new` in the command -- that is
  what made adding a second transport a change to `RunCommand`. A definition whose `sourceId` differs
  from the mapping's is refused: it would map cleanly and produce well-formed records about the wrong
  feed, which no contract catches.
- **A Kafka record timestamp serves two masters, and the broker is one of them.** The platform reads
  `CREATE_TIME` as what the source asserts (§4.7 freshness); Kafka reads the same value for
  *retention*. A simulated feed dated six months back is expired by the broker as fast as it is
  written -- the log start offset jumps to the end, and a consumer on `earliest` correctly finds
  nothing. The first end-to-end run published 250 records, landed 0, and exited 0 with every log line
  healthy. `simulate` therefore defaults `--feed-start` to now; pin it only when reproducing a feed.
  A fixed date is fine in a fixture file and wrong on a broker.
- **A Kafka slice must not start its idle window before the group assigns partitions.** `subscribe()`
  is not joining: the first polls return empty while the coordinator forms the group, and a broker's
  `group.initial.rebalance.delay.ms` alone is three seconds by default. With a short `idleMillis` the
  slice ends mid-rebalance and reports an empty topic. `KafkaSourceHandle` waits for a non-empty
  `assignment()` first, and throws if one never arrives -- a broken subscription is not an empty
  topic, and must never be reported as landing nothing.
- **Configuring a connector asks whether the settings are well-formed; `health()` asks whether the
  source can be reached.** `FileDropConnector.configure()` used to refuse a drop directory that did
  not exist -- a check `health()` already makes, and one that made a source impossible to *describe*
  without standing in its environment. A records manager reviewing a definition on their laptop has
  no `/var/spool/cad`. Nothing is lost: `run` asks `health()` before it lands anything, so an
  operator still finds out before a batch is half-committed. Do not put reachability back into
  `configure()`.
- **The catalogue says how a source arrives, and configuring is how it finds out.** `Catalogue.Arrival`
  asks the connector for its interaction mode and retention rather than reading settings itself --
  retention is configuration for Kafka and a constant for a file drop, and only the connector knows
  which. One arrival per definition, never flattened to one transport per source: Riverton CAD
  arrives both ways under one mapping, and that is the separation worth showing. A definition that
  cannot be described is listed with its reason, because silently missing is indistinguishable from
  never configured.
- **`niem simulate` is a separate command from `run`, not a mode of it.** A simulator switchable on
  inside an ingest is one that gets switched on by accident against a real bronze store, and synthetic
  records mixed into landed agency data cannot be taken back out. `CadSimulator` lives in the domain
  module (it produces CAD rows) and the CLI owns the transport. Deterministic from a seed, and every
  person in it invented (ADR 0013).
- **`Record.toString()` never prints values** — deliberately, see
  [ADR 0015](docs/decisions/0015-records-redact-values.md). Any new type carrying record values
  (envelopes, quarantine entries, lineage events) inherits this obligation. The compiler will
  not enforce it.

---

## Open questions — do not resolve unilaterally

| # | Question | Blocks | Status |
|---|---|---|---|
| §2 / §10.1 | ~~Control plane language~~ | — | **Resolved: JVM** — [ADR 0021](docs/decisions/0021-control-plane-is-jvm.md). The deciding constraint was one validator per artifact format, never two ([ADR 0020](docs/decisions/0020-control-plane-boundary.md)). |
| §10.3 | ~~Multi-tenancy model~~ | — | **Resolved: a tenant is an agency, not a deployment** — [ADR 0025](docs/decisions/0025-tenancy-and-federation.md). One deployment may host one tenant or many; tenants federate. Cluster identities are tenant-seeded so two agencies cannot merge by accident. |
| §2 / §6 | Whether an agency may ever let an advisor read sampled record values | Advisor quality | Open. Phase 1 proceeds on shapes only — [ADR 0023](docs/decisions/0023-mapping-advisor.md). Changing it needs its own ADR and an agency-level opt-in, not a config flag. |
| §2 / ADR 0005 | Canonical table format if Iceberg cannot run embedded on Windows | Silver storage | Pending a spike. Deviating from the pinned Delta/Iceberg decision needs Jeff's call. |

Resolved during implementation: §10.2 (canonical DSL — ADR 0012), §10.4 (synthetic CAD CSV —
ADR 0013), §10.5 (deterministic resolver — ADR 0014).

**Built:** completeness accounting — `RecordAccount`, `CompletenessBreach`, and a `Completeness:`
line on every `run`. The invariant is `produced + quarantined + skipped = landed x hops`. **The
pipeline keeps the books, not the caller**: reading only `canonicalRecords()` is the natural thing
to write and is exactly how a drop becomes invisible, because §4.2 makes quarantining routine and a
short count then looks like an ordinary run over a messy feed. A quarantined or skipped hop
*balances* — the platform can show you that record; only arithmetic that does not add up is a
breach. `run` exits **4** on a breach, which outranks 2 (quarantined) and 0 (clean). Checked after
everything is processed, never mid-run: halting on a residual would abandon records about to map
fine and make the residual larger. On Flink the driver still cannot see inside its operators, so
`MappingOperator.close()` emits the breach to the event stream — the finding escapes even though no
count reaches the driver, and the driver still exits 3.

**Built:** the graph is readable, not only writable — `Neo4jIncidentReader` and `niem incident`.
An incident, everyone on it with their role, and where those people appear on other incidents,
which is the only question a graph is needed for. **Named `incident`, not `case`, deliberately**:
the canonical model has `Incident` and no `Case`, and a case aggregates incidents and carries a
lifecycle, so `niem case` would promise an aggregation that does not exist. A test asserts there is
no `case` subcommand. Label and relationship naming moved into `GraphNaming`, shared by reader and
writer — a reader matching `PersonIncidentAssociation` against a written
`PERSON_INCIDENT_ASSOCIATION` returns an empty result that looks exactly like an incident with
nobody on it. The view records redact values in `toString` per ADR 0015; a view is *more* exposed
than a record, because it is assembled to be looked at and so reaches logs a record never does.

**Built:** the NIEM coverage browser — `niem coverage`, `/api/coverage`, and the NIEM view in the
authoring surface. Answers "conformant to how much of NIEM?": 2 of 18 domains, 16 of 27,148
declarations, with both extensions and their written reasons. Untouched domains are listed
deliberately — coverage without a denominator is not coverage. It is a **report, not a validator**:
every rule about what a citation may say stays in the code generator (ADR 0020). When it needed one
of those rules — that `nc:IncidentType` and `IncidentType` name the same thing — the rule moved into
the shared `NiemRelease.localName` and the validator calls it, rather than a second copy growing here.

**Built:** the catalogue documents each source's own vocabulary against canonical terms, not only
NIEM provenance — [ADR 0019](docs/decisions/0019-catalogue-source-vocabulary.md). `niem catalogue`,
and `Catalogue` in the control plane. `--gaps-only` exits 2 when a term is undocumented or unread,
so onboarding can require a source to be explained before it is accepted.

---

## Phase 1 progress

- [x] Gradle multi-module scaffold, version catalogue, convention plugins, wrapper
- [x] Canonical DSL, validator, code generator, `Person` / `Incident` / association
- [x] ADRs for every pinned §2 decision and every decision taken since
- [x] Hop contracts and validation (§4.2)
- [x] Observability event taxonomy (§4.7)
- [x] Connector SPI and file drop connector (§4.3)
- [x] Bronze landing (§4.4)
- [x] Canonical silver store on Iceberg (§2, ADR 0005)
- [x] Identity resolution (§4.5)
- [x] Mapping DAG on embedded Flink (§5) — criterion 7 proved
- [x] Graph projection to Neo4j (§4.6) — criterion 4 proved
- [x] Replay driver (§5) — criterion 6 proved
- [x] LE module fixtures and mappings
- [x] Operator CLI: `validate`, `describe`, `author`, `run`, `replay`, `inspect` — all of §8's list
- [x] Mapping authoring surface (§4.8, ADR 0021): field-level flow canvas, direct editing,
      versioned save. No YAML required to author a mapping.
- [x] All 7 acceptance criteria asserted in tests
- [x] Containers, Helm chart, air-gapped packaging (§6)

- [x] NIEM references verified against a real release (§4.1, ADR 0011)

**Acceptance criterion 7** — the identical mapping definition running unchanged in batch and
streaming, producing identical canonical output — is the one that validates the core
architectural bet. Phase 1 is not done without it.

---

## Phase 2 progress

Opened by Jeff on 2026-09-09. Spec §8 scopes it as: Kafka, MQTT, CDC connectors; search projection;
catalogue, lineage, stewardship, approval workflow; AI-assisted mapping authoring; control plane
language decision resolved (done — ADR 0021).

**Agreed connector order: Kafka, then CDC, then FTP.** The first one had to pay for the
connector-agnostic operator surface (ADR 0029); the rest cost only their own module.

- [x] Kafka connector (§4.3) — bounded slices, acknowledge-after-commit, retention per source
      ([ADR 0028](docs/decisions/0028-streaming-sources-land-in-bounded-slices.md))
- [x] `SourceDefinition` — a source is an artifact, `run --source` resolves it through the registry
      ([ADR 0029](docs/decisions/0029-a-source-is-an-artifact.md))
- [x] `niem simulate` and `CadSimulator` — a synthetic CAD feed, so a live ingest can be exercised
- [x] Transport in the catalogue — `niem catalogue`, `/api/catalogue`, and an ARRIVES section in the
      authoring surface. Delivers ADR 0027's stated consequence that a non-retainable source "has to
      be visible in the catalogue"
- [ ] CDC connector — next. Log sequence number is an `acknowledge()`; the rest is per-vendor mess
- [ ] FTP connector — the archive move is its `acknowledge()`
- [ ] MQTT connector
- [ ] Search projection (Elasticsearch, [ADR 0007](docs/decisions/0007-elasticsearch-search-projection.md),
      still marked Deferred — it needs its status changed and a document-shape ADR when it starts)
- [ ] Lineage, stewardship, approval workflow
- [ ] `niem validate` covering source definitions — currently only checked when `run` loads one

**Not carried over from Phase 1's rules:** nothing. Rule 1 still holds, with Phase 2 as the line.
