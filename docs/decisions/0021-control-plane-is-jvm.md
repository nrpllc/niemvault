# 0021. The control plane is JVM

**Status:** Accepted · resolves spec §10.1, the question §2 said not to resolve unilaterally

## Context

Spec §2 left the control plane language open and instructed that the decision be surfaced before
any control plane code was written. §10.1 listed it as blocking Phase 2. It was surfaced and Jeff
settled it: **JVM**.

The immediate trigger was the authoring surface. Asked where the experience to edit an end-to-end
flow was, the honest answer was that there wasn't one — a mapping is authored by editing YAML in a
text editor and running `niem validate`. Two rendered diagrams had been built in the meantime,
which show a flow and do not let anyone change one. That gap is what this decision unblocks.

## Decision

The control plane — catalogue, mapping authoring, approval workflow — is JVM, served in-process
with the validators it depends on.

The deciding constraint is the one recorded in ADR 0020: **there is exactly one validator per
artifact format, and the control plane does not get its own.** Everything an authoring surface must
check already exists and is tested:

| The editor must | Component |
|---|---|
| Parse and validate a mapping | `MappingLoader` |
| Reject a bad transform before it runs | `TransformFactory` |
| Check contracts against the hops that name them | `ContractLoader`, `ArtifactSet` |
| Refuse content this platform cannot run | `ModuleManifestLoader` |
| Validate the canonical model | `CanonicalModelValidator` |

On the JVM these are method calls. Across a language boundary they are an API to design, version,
and keep in step. Both are workable; only one is free.

## Consequences

**One runtime in the air-gapped package.** §6 makes air-gapped delivery mandatory, and a second
language would mean a second runtime in every offline bundle for every agency.

**No CDN, no build step, no framework.** The same §6 constraint rules out fetching anything at
runtime. The authoring UI is served from the platform's own resources, and the DAG is rendered as
SVG by the server rather than by a JavaScript diagramming library — which also removes the
dependency the two published diagram pages leaned on.

**Editing creates a new mapping version rather than overwriting one.** Mappings are versioned
artifacts (§7) and changes must be attributable (§4.8). A published mapping is not edited in place;
an edit drafts the next version, leaving the original exactly as it was reviewed. This also
sidesteps a real problem: the shipped mapping carries explanatory comments that a YAML round trip
would silently strip.

**This does not authorise the rest of Phase 2.** Stewardship routing and the approval workflow stay
unbuilt. The authoring surface plus a read-only catalogue is the increment; §4.8's governance is a
separate piece of work.

**Reversible at a cost.** If a .NET UI is later wanted, it calls the same validators over an API.
What must never happen is a second implementation of them.
