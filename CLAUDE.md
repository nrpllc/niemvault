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
- **Large Java files via bash heredoc are fragile.** Use the Write tool.
- **`gradlew test` does not run `build-logic` tests.** It is an included build, so its tasks
  are not matched by name from the root. Use `gradlew testAll`, which depends on both. The
  canonical model code generator lives in `build-logic` and has the most rules per line in the
  project — a green run that silently skipped it would be worse than no run.
- **Zen sidecar caveat: `testgaps` under-reports Java coverage.** Its Java call graph does not
  bind calls from test sources into main sources, so symbols that tests exercise directly are
  listed as having 0 callers — `ValueShape.of` is invoked 9 times from `ObservabilityEventTest`
  and still appears at the top of the gap list. Treat the ranking as a hint, not a target, and
  do not write tests to move the number.
- **`Record.toString()` never prints values** — deliberately, see
  [ADR 0015](docs/decisions/0015-records-redact-values.md). Any new type carrying record values
  (envelopes, quarantine entries, lineage events) inherits this obligation. The compiler will
  not enforce it.

---

## Open questions — do not resolve unilaterally

| # | Question | Blocks | Status |
|---|---|---|---|
| §2 / §10.1 | Control plane language: JVM or .NET | Phase 2 | Open. No control plane code exists yet, so nothing is blocked today. |
| §10.3 | Multi-tenancy model | Storage layout, policy | Open. Phase 1 proceeds on a stated *assumption* of one tenant per deployment — [ADR 0016](docs/decisions/0016-single-tenant-phase1.md). |
| §2 / ADR 0005 | Canonical table format if Iceberg cannot run embedded on Windows | Silver storage | Pending a spike. Deviating from the pinned Delta/Iceberg decision needs Jeff's call. |

Resolved during implementation: §10.2 (canonical DSL — ADR 0012), §10.4 (synthetic CAD CSV —
ADR 0013), §10.5 (deterministic resolver — ADR 0014).

---

## Phase 1 progress

- [x] Gradle multi-module scaffold, version catalogue, convention plugins, wrapper
- [x] Canonical DSL, validator, code generator, `Person` / `Incident` / association
- [x] ADRs for every pinned §2 decision and every decision taken since
- [x] Hop contracts and validation (§4.2)
- [x] Observability event taxonomy (§4.7)
- [x] Connector SPI and file drop connector (§4.3)
- [x] Bronze landing (§4.4)
- [ ] Identity resolution (§4.5)
- [ ] Mapping DAG on embedded Flink (§5)
- [ ] Graph projection to Neo4j (§4.6)
- [ ] Replay driver (§5)
- [ ] LE module fixtures and mappings
- [ ] Operator CLI: `validate`, `run`, `replay`, `inspect`
- [ ] All 7 acceptance criteria asserted in tests
- [ ] Containers, Helm, manifests (§6)

**Acceptance criterion 7** — the identical mapping definition running unchanged in batch and
streaming, producing identical canonical output — is the one that validates the core
architectural bet. Phase 1 is not done without it.
