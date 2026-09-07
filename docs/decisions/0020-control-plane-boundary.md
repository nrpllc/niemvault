# 0020. The control plane's boundary, and the domain module contract

**Status:** Accepted for the boundary · the control plane itself is **not built** · §10.1 remains open

## Context

Jeff asked whether defining the control plane early would help keep domain modules consistent with
one another. It would — but "define" and "build" have to be separated, because spec §2 is explicit:

> **Open — do not resolve unilaterally:** the control plane (catalogue, mapping authoring UI,
> approval workflow) may be .NET rather than JVM… **Surface this decision before writing any
> control plane code.**

And §10.1 lists the language question as blocking Phase 2. So the boundary can be defined now; no
control plane code may be written until the language is settled.

The timing argument for defining it now is real and has nothing to do with the UI. The shape of a
domain module is being set **right now**, by the only module that exists. Whatever
`modules/law-enforcement` looks like is what the second domain module will copy, and the moment
there are two, inconsistencies stop being free to fix.

## Decision

### What the control plane owns

Authoring and governance surfaces, and nothing else:

- the catalogue, presenting registered assets and each source's vocabulary (ADR 0019);
- mapping authoring — drafting, editing, and diffing a mapping against the deployed version;
- stewardship routing and the approval workflow (§4.8).

### What it must never own

**Validation.** `CanonicalModelValidator`, `ContractLoader`, `MappingLoader`, `SchemaValidator` and
`ModuleManifestLoader` are the authority on whether content is valid, and there must be exactly one
of each. A control plane that re-implemented them would produce two validators for one artifact
format, and two validators drift. When they drift, the authoring UI accepts a mapping the runtime
rejects — or worse, one the runtime interprets differently — which is the platform's own founding
failure mode reintroduced at the authoring layer.

This constrains the language decision more than it first appears. A JVM control plane calls the
validators directly. A .NET control plane must call them over an API rather than reimplement them,
which is a real cost but a payable one. A .NET control plane with its own validators is not an
option.

### The domain module contract

Every domain module ships:

| | | |
|---|---|---|
| `module.yaml` | Manifest: name, content version, platform range, canonical model version, contributed namespaces, steward | **Enforced at load** |
| `canonical/*.yaml` | Canonical type definitions, if the module contributes any | Validated at build |
| `mappings/*.yaml` | Versioned mapping artifacts | Validated at load |
| `contracts/*.yaml` | Hop contracts, one per hop | Validated at load |
| `fixtures/` | Reference source data, including a drifted variant | Exercised by tests |

**Cross-domain consistency comes from this contract and the validators behind it, not from the
control plane.** The UI is where a human meets the rules; it is not where the rules live. That
ordering is what lets the CLI, a future UI, and the runtime all agree without coordination.

## Consequences

- **A §7 gap is now closed.** §7 requires compatibility ranges "declared explicitly in content
  metadata and enforced at load", and nothing declared one. `module.yaml` and
  `ModuleManifestLoader` add both, and `niem validate` refuses content this platform cannot honour
  before reading any of it.
- **The second domain module has something to copy.** It was previously an undocumented shape held
  in one module's directory layout.
- **A read-only catalogue needs no language decision.** `niem catalogue list|show` over artifacts
  that already describe themselves is most of the catalogue's value and forecloses nothing. It is
  the sensible first increment whenever the catalogue is scheduled.
- **Still open, and still Jeff's:** §10.1, the control plane language. Nothing here resolves it,
  and the constraint above is an input to that decision rather than a resolution of it.
