# NIEM Integration Platform — Build Specification

**Audience:** Claude Code (agentic implementation)
**Status:** Draft v0.1 — Phase 1 scoped, later phases outlined
**Owner:** Jeff

---

## 0. How to use this document

This spec is written to be executed, not admired. Rules for the implementing agent:

1. **Do not build ahead of the current phase.** Phase boundaries are deliberate. Phase 1 acceptance criteria are the definition of done.
2. **Pinned decisions are pinned.** Where this document says "Decision:", treat it as settled. Where it says "Open:", stop and ask before proceeding.
3. **Contracts before implementation.** Every module boundary in §4 has a defined interface. Write the interface and its tests first.
4. **No placeholder code.** If a component can't be built without an unresolved decision, stop and surface the question rather than stubbing something plausible.
5. **Every mapping, schema, and transformation is a versioned artifact on disk.** Nothing meaningful lives only in a database row.

---

## 1. Problem and value proposition

Government agencies spend disproportionately on point-to-point integration — agency to agency, and local to state to federal. Each integration is bespoke, undocumented, and breaks silently.

This platform makes that integration a solved, licensed capability:

- A canonical data core derived from NIEM, extended where the standard falls short.
- A mapping layer that turns any source into that canonical form, with the mappings themselves as the reusable, compounding asset.
- Domain modules (law enforcement first) licensed independently.
- Governance, lineage, and observability built in — because the external tooling agencies would otherwise use is unavailable or badly lagged in GCC High, Azure Government, and on-prem environments.

**Competitive position:** the incumbents (Axon, Motorola) win on incumbency and lock-in, not technology, and are deliberately weak at interoperability. The wedge is to become the integration fabric between them, then move upward into applications once the data layer is owned.

---

## 2. Pinned technology decisions

| Concern | Decision | Rationale |
|---|---|---|
| Ingest → canonical runtime | **Java 21 + Apache Flink** | Unified batch/streaming (batch as bounded stream), so one transformation definition serves both modalities. Embeds single-node for small agencies; scales to cluster for federal. |
| Canonical → gold runtime | **Apache Spark**, or warehouse-native SQL where available | Mature optimiser for large shuffles/joins. Optional, not required for Phase 1. |
| Build tool | Gradle (Kotlin DSL) | |
| Raw storage | Object store, append-only, Parquet + sidecar JSON envelope | Lossless, replayable, audit-friendly |
| Canonical storage | Delta Lake (or Iceberg) tables | Time travel supports lineage and replay |
| Graph projection | Property graph — **Neo4j** for Phase 1 | Fastest path; abstracted behind a writer interface (§4.6) so it is swappable |
| Search projection | Elasticsearch | Deferred to Phase 2 |
| Warehouse projection | Deferred to Phase 3 | |
| Deployment | Containers + Kubernetes manifests / Helm | One artifact serves both managed and air-gapped delivery |
| Config format | YAML, schema-validated on load | |

**Open — do not resolve unilaterally:** the control plane (catalogue, mapping authoring UI, approval workflow) may be .NET rather than JVM. Recommendation is to keep Phase 1 single-language JVM to avoid cross-language friction, and revisit before Phase 2. Surface this decision before writing any control plane code.

---

## 3. Repository layout

```
niem-platform/
  build.gradle.kts
  settings.gradle.kts
  CLAUDE.md                     # agent working notes; keep current
  docs/
    spec.md                     # this document
    decisions/                  # one ADR per pinned decision, numbered
  core/
    canonical/                  # NIEM-derived canonical model + extensions
    contracts/                  # hop contract definitions and validator
    lineage/                    # lineage event model and emitter
  runtime/
    engine/                     # Flink job graph construction, execution
    transforms/                 # transformation primitives
    replay/                     # bronze replay driver
  connectors/
    api/                        # SourceConnector interface + SPI registration
    file/                       # Phase 1: file drop connector
    kafka/                      # Phase 2
    cdc/                        # Phase 2
  identity/
    api/                        # ResolutionProvider interface
    internal/                   # bundled default resolver
  projections/
    api/                        # ProjectionWriter interface
    graph/                      # Neo4j writer
  modules/
    law-enforcement/            # first domain module
      canonical/                # domain-specific canonical extensions
      mappings/                 # versioned mapping artifacts
      fixtures/                 # sample source data for tests
  governance/
    catalogue/                  # asset registry, stewardship
    policy/                     # access + purpose-limitation enforcement
  deploy/
    helm/
    manifests/
  tools/
    cli/                        # operator CLI: validate, replay, inspect
```

Package root: `gov.niemplatform`.

---

## 4. Component contracts

Each subsection defines a module boundary. Implement the interface and its test suite before the implementation behind it.

### 4.1 Canonical model

The canonical core is NIEM-derived, not strictly NIEM-conformant. Extensions are permitted where the standard falls short, but:

- Every canonical type declares its NIEM provenance (namespace + type) or explicitly declares itself an extension with a written justification.
- Extensions live in a separate namespace from NIEM-sourced types. Never silently redefine a NIEM type.
- The canonical model is versioned independently of the platform (§7).

Represent canonical types as generated Java records from a schema definition, not hand-written classes. Schema definitions are the source of truth.

**Phase 1 scope:** `Person`, `Incident`, and the association between them. Nothing else.

### 4.2 Contracts and validation

Every hop in the mapping graph declares what it expects and what it emits.

```java
public interface HopContract {
    ContractId id();
    Schema expects();
    Schema emits();
    ValidationResult validate(Record record, Direction direction);
}
```

Requirements:

- Validation runs on **both** input and output of every hop. This is the mechanism for catching semantic drift, which is the failure mode that matters — not hard pipeline failures, but a source quietly changing format while nothing errors.
- A violation emits a structured observability event (§4.7) and routes the record to a quarantine path. It does not silently drop, and does not by default halt the pipeline.
- Contracts are versioned artifacts on disk, not code.

### 4.3 Connectors

```java
public interface SourceConnector {
    ConnectorType type();
    void configure(ConnectorConfig config);
    SourceHandle open();          // emits RawEnvelope
    HealthStatus health();
    void close();
}
```

Onboarding a source has two independent halves, and the code must reflect that separation:

1. **Transport configuration** — boilerplate. Kafka, webhook, MQTT, ODBC, CDC, file drop.
2. **Schema mapping** — where the value is. Sample data in, proposed canonical mapping out, human review and approval.

Every connector lands into bronze identically. Transport differences must not leak past the landing boundary.

**Phase 1:** file drop connector only.

### 4.4 Raw landing (bronze)

Append-only and immutable. Every landed record is wrapped in an envelope carrying:

- Source identifier and connector instance
- Ingest timestamp and source-asserted timestamp
- Raw payload, byte-preserved
- Content hash
- Batch or stream offset

Bronze is never mutated or deleted by platform logic. Silver and gold must be fully rebuildable from bronze — this is both a correctness property and an audit requirement.

### 4.5 Identity resolution

```java
public interface ResolutionProvider {
    ResolutionResult resolve(EntityAttributes attributes);
    ProviderCapabilities capabilities();
}

public record ResolutionResult(
    ClusterId clusterId,
    double confidence,
    List<MatchEvidence> evidence
) {}
```

Design constraints:

- Providers are pluggable. Agencies with existing investment (Senzing, IBM entity analytics) plug theirs in behind this interface.
- The platform **always** maintains its own thin index of cluster identifiers, even when an external provider does the resolution work. Without it, cross-domain joins in the graph are impossible.
- A bundled default resolver ships in `identity/internal` for agencies without an existing capability. Same contract, different engine.
- Never persist an external provider's internal state. Persist only the returned cluster identifier, confidence, and evidence.

### 4.6 Projections (gold)

```java
public interface ProjectionWriter {
    ProjectionType type();
    void apply(CanonicalChangeSet changes);
    void rebuild(CanonicalSnapshot snapshot);
}
```

Gold is polymorphic: the same canonical silver source projects into multiple shapes.

- **Graph** — primary. NIEM's association structures are already graph edges; this projection honours the model rather than flattening it. Phase 1.
- **Search** — Elasticsearch. This is what an investigator actually opens first, so it matters commercially even though graph comes first architecturally. Phase 2.
- **Warehouse** — relational/star schema for BI, analytics, and ML feature engineering. Phase 3.

Every projection must support full rebuild from silver.

### 4.7 Observability

Structured events, not log strings. Minimum event taxonomy:

- `ContractViolation` — hop, direction, expected vs actual, sample
- `SourceDrift` — statistical deviation in field shape or cardinality against a rolling baseline
- `PipelineLag` — per-source freshness against declared SLA
- `ResolutionAnomaly` — confidence distribution shift
- `ProjectionDivergence` — projection row/node count vs silver expectation

Drift detection is a first-class requirement, not a nice-to-have. The failure this platform exists to prevent is silent corruption, not visible crashes.

### 4.8 Governance

Built in, not integrated. Required from Phase 2:

- **Catalogue** — every source, mapping, contract, canonical type, and projection registered as an asset.
- **Lineage** — column-level, traceable from any gold field back to the bronze record and the mapping version that produced it.
- **Stewardship** — ownership assigned per domain and per source; mapping changes route to the responsible steward for approval.
- **Policy** — access and purpose limitation enforcement, with CJIS constraints as the reference implementation.

Every mapping change is a versioned, reviewable, attributable artifact. Auditors will ask who approved what and when; the system must answer without archaeology.

---

## 5. Execution model

**Non-negotiable principle: one transformation definition, two execution modalities.** The same mapping logic runs in streaming and batch without modification. This is the primary reason for the Flink decision and must not be compromised for convenience.

Engine boundary:

- **Bronze → Silver: Flink.** Per-record mapping, validation, identity resolution, canonicalisation. Flink's sweet spot.
- **Silver → Gold: Spark or warehouse SQL.** Batch aggregation and reshaping, large joins. Flink is weaker here and should not be forced into it.

Mapping definitions are declarative artifacts compiled into a Flink job graph at deploy time. Mappings are data, not code — an agency updating a mapping must not require a platform rebuild.

Replay is a first-class operation: given a bronze range and a mapping version, rebuild silver deterministically.

---

## 6. Deployment

Two delivery modes, one artifact:

1. **Managed** — governed updates pushed into the customer environment from the vendor cloud.
2. **Air-gapped** — installable package applied by the customer. Many criminal justice environments will not permit outbound connectivity, so this path is mandatory, not a fallback.

Everything is containerised and declarative. There is no separate on-prem codebase and no on-prem-only code path. If a feature can't work air-gapped, it is designed wrong.

---

## 7. Versioning

Platform version and content version move independently — engine versus definitions.

- **Platform:** engine, connectors, runtime, projections.
- **Content:** canonical schema version, mapping versions, contract versions, domain module content.

An agency on an older platform release must be able to take newer justice-domain mappings and NIEM releases. Compatibility ranges are declared explicitly in content metadata and enforced at load. This makes the change-board conversation tractable, which is a commercial requirement as much as a technical one.

---

## 8. Phase plan

### Phase 1 — Vertical slice (current scope)

Prove the spine end to end with the narrowest possible surface.

**In scope:**
- File drop connector
- One law enforcement source with realistic fixtures
- `Person`, `Incident`, and their association only
- Bronze landing with full envelope
- Mapping DAG with contract validation on every hop, running on embedded single-node Flink
- Bundled default identity resolver; provider interface defined and exercised by a test double
- Graph projection to Neo4j
- Replay from bronze
- Observability events emitted and asserted in tests
- Operator CLI: `validate`, `run`, `replay`, `inspect`

**Explicitly out of scope:** Kafka/MQTT/CDC connectors, search and warehouse projections, catalogue UI, approval workflow, any client application, Spark, multi-tenancy, authentication beyond a stub.

**Acceptance criteria:**
1. A sample source file lands in bronze byte-preserved, with a complete envelope.
2. That record maps to canonical `Person` and `Incident` with contract validation passing on both directions of every hop.
3. Identity resolution assigns a cluster ID; two source records for the same human resolve to one cluster.
4. The graph projection contains the expected nodes and the association edge.
5. Deliberately corrupting a source field shape produces a `ContractViolation` event and a quarantined record — the pipeline does not halt and does not silently pass bad data.
6. Deleting silver and gold and running replay reproduces both exactly.
7. The identical mapping definition runs unchanged in both batch and streaming mode, producing identical canonical output.

Criterion 7 is the one that validates the core architectural bet. Do not declare Phase 1 done without it.

### Phase 2 — Breadth and governance
Kafka, MQTT, CDC connectors. Search projection. Catalogue, lineage, stewardship, approval workflow. AI-assisted mapping authoring. Control plane language decision resolved.

### Phase 3 — Applications
Warehouse projection. Client shells — shared core with desktop, tablet, and mobile targets. Offline sync with conflict resolution and a durable queue that survives patrol dead zones; this is genuine engineering, not a framework feature.

### Phase 4 — Module expansion
Second domain module. Licensing and entitlement enforcement per module.

---

## 9. Conventions

- **Tests:** every module boundary has contract tests. Fixtures live beside the module. Integration tests use testcontainers.
- **Errors:** typed and structured. No exception carries a bare string as its only payload.
- **Config:** schema-validated on load; fail fast and loudly on invalid config.
- **Logging:** structured. Reserve unstructured logs for developer diagnostics only.
- **ADRs:** every pinned decision in §2 gets a numbered ADR in `docs/decisions/`. New decisions taken during implementation get one too.
- **CLAUDE.md:** kept current with working context, gotchas, and anything a fresh session would need.

---

## 10. Open questions

Resolve with Jeff before the phase that depends on them.

1. Control plane language — JVM or .NET (§2). Blocks Phase 2.
2. Canonical schema definition format — XSD-derived from NIEM directly, or an intermediate DSL with NIEM import. Affects code generation in §4.1.
3. Multi-tenancy model — single tenant per deployment, or shared with logical isolation. Affects storage layout and policy enforcement.
4. Which specific law enforcement source is the Phase 1 reference — CAD or RMS, and which vendor's export shape.
5. Whether the bundled default resolver is deterministic-rules-only for Phase 1, or probabilistic from the start.
