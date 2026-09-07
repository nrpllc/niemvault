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

### ⚠ NIEM references are unverified

The `niemNamespace` / `niemType` / `niemElement` values in the DSL are asserted from
knowledge. **No NIEM 6.0 release is on disk to check them against.** See
[ADR 0011](docs/decisions/0011-niem-reference-verification.md).

Phase 1 cannot be signed off until they are verified, ideally by a build-time validator that
resolves each reference against a NIEM release manifest. `PersonIncidentAssociation` is
deliberately modelled as an **extension** for this reason — NIEM plainly has the machinery,
but we could not cite the exact type, and a guessed provenance is worse than none.

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
- **Node coordinates are never written to a mapping** -- [ADR 0022](docs/decisions/0022-flow-layout-is-computed.md).
  Layout is recomputed; dragging is session-only.
- **The control plane carries no validators of its own** -- ADR 0020/0021. `/api/validate` and
  `/api/edit` both call `MappingLoader`, `TransformFactory`, `ContractLoader` and
  `ModuleManifestLoader`. If a check is missing from the editor, add it to the loader, never to the
  editor.
- **`scrollIntoView({behavior: "smooth"})` is silently ignored** where reduced motion is in effect,
  including some automation contexts. A jump that sometimes does not happen is worse than one that
  never animates -- the authoring UI uses instant scrolling deliberately.
- **`Record.toString()` never prints values** — deliberately, see
  [ADR 0015](docs/decisions/0015-records-redact-values.md). Any new type carrying record values
  (envelopes, quarantine entries, lineage events) inherits this obligation. The compiler will
  not enforce it.

---

## Open questions — do not resolve unilaterally

| # | Question | Blocks | Status |
|---|---|---|---|
| §2 / §10.1 | ~~Control plane language~~ | — | **Resolved: JVM** — [ADR 0021](docs/decisions/0021-control-plane-is-jvm.md). The deciding constraint was one validator per artifact format, never two ([ADR 0020](docs/decisions/0020-control-plane-boundary.md)). |
| §10.3 | Multi-tenancy model | Storage layout, policy | Open. Phase 1 proceeds on a stated *assumption* of one tenant per deployment — [ADR 0016](docs/decisions/0016-single-tenant-phase1.md). |
| §2 / ADR 0005 | Canonical table format if Iceberg cannot run embedded on Windows | Silver storage | Pending a spike. Deviating from the pinned Delta/Iceberg decision needs Jeff's call. |

Resolved during implementation: §10.2 (canonical DSL — ADR 0012), §10.4 (synthetic CAD CSV —
ADR 0013), §10.5 (deterministic resolver — ADR 0014).

**Phase 2, decided early so it is not lost:** the catalogue documents each source's own vocabulary
against canonical terms, not only NIEM provenance — [ADR 0019](docs/decisions/0019-catalogue-source-vocabulary.md).
It stays unbuilt until Phase 1 acceptance is complete. The one thing to remember meanwhile: when
the mapping and contract artifact formats are next revised, give each declared column and step an
optional `doc:`. That meaning is free to capture while authoring and expensive to recover later.

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
- [ ] Containers, Helm, manifests (§6)

**Acceptance criterion 7** — the identical mapping definition running unchanged in batch and
streaming, producing identical canonical output — is the one that validates the core
architectural bet. Phase 1 is not done without it.
