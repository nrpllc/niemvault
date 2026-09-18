# 0034 — What this platform submits is configuration, not a class

**Status:** Accepted
**Date:** 2026-09-15
**Context:** §0.5 (everything is a versioned artifact), §4.3 (connectors), §4.6 (projections), §9 (config validated on load), ADR 0010, ADR 0027, ADR 0029
**Implements:** `exchange:api`, `exchange:cch`; replaces `projections:cch`

## Context

The first criminal history writer was a `ProjectionWriter` with a well-argued javadoc, and it could
not submit a criminal history record.

It named three canonical types in constants:

```java
private static final String PERSON = "Person";
private static final String INCIDENT = "Incident";
private static final String ASSOCIATION = "PersonIncidentAssociation";
```

and listed the fields one Java line at a time:

```java
put(node, "givenName", field(typed, record, "givenName"));
put(node, "surName",   field(typed, record, "surName"));
```

Three of the sixteen canonical types, and none of the six the criminal history cycle is made of —
no `Arrest`, `Charge`, `Disposition`, `Booking`, `Sentence` or `BiometricSubmission`. Adding a charge
to a submission was a code change and a platform release.

There is a worse problem underneath the inconvenient one. Those `put(node, "field", ...)` lines
**are a mapping engine written in Java**, with no contract gate, no provenance, no version and no
quarantine path. This platform already has a mapping engine with all four. Two mapping engines
drift, and the one without contracts is the one carrying criminal history.

The operator surface had the same shape: `--cch-url` named one repository on the command line, which
is exactly what ADR 0029 rejected for sources — a flag list that grows by one exchange per exchange,
with the settings behind each of them in someone's shell history.

## Decision

### 1. An exchange is a versioned artifact

The outbound mirror of a source definition. It names the wire format, the endpoint, the source it
sends, and — the part that used to be Java — what is assembled.

### 2. Assembly is a walk over associations the model already declares

```yaml
assemble:
  root: Arrest
  follow:
    - association: ArrestChargeAssociation
      role: charge
      as: charges
      follow:
        - association: ChargeDispositionAssociation
          role: disposition
```

Not a parent/child join tree. The relationships are already in the canonical model, with roles,
targets and NIEM provenance; restating them here would be a second description of one structure, and
two descriptions can disagree — as a charge attached to the wrong arrest.

It also means an exchange **cannot invent an edge the model lacks**. A submission that needs one is a
modelling gap, fixed where it is versioned and attributed, rather than papered over in one
exchange's configuration.

The association is carried into the document, not discarded. `ArrestSubjectAssociation` says whether
that subject was read their rights, and `PersonIncidentAssociation` says victim or suspect; a walk
that kept only the endpoints would lose exactly the fields that say what the relationship means, and
they are unrecoverable from the two records it joined.

### 3. Validated against the model on load, because the failure is silent

Every step is checked before anything runs: that the association exists, that it is an association,
that it declares the named role, that it actually touches the type in hand, that the walk does not
loop, that two elements do not claim one name.

The anchoring check is the one that earns its keep. Following `ChargeDispositionAssociation` from an
`Arrest` reads perfectly well and is wrong — the association touches `Charge`. Unvalidated it
assembles nothing, the document's optional elements are simply absent, the repository accepts it
without complaint, and nobody finds out until someone asks why no charge has ever had a disposition.

### 4. Submission is not a projection

The rejected alternative, and it was a close thing. §4.6 makes gold polymorphic and a repository does
consume canonical records, so a repository looks like another shape of gold.

The argument breaks on one method. `ProjectionWriter.rebuild` must *replace* the projection from a
complete snapshot of silver, because criterion 6 turns on a projection being reconstructible. Against
a graph that is routine. Against a state criminal history repository it means resubmitting every
arrest the agency has ever made — not a rebuild, an incident, and the repository has no obligation to
tolerate it. A projection this platform can rebuild and a record system it can only append to are
different things, and one interface covering both would be honest about only one of them.

So `ExchangeWriter` gets what submission actually needs: a receipt per document, `ACCEPTED` /
`REJECTED` / `PENDING`, and the identifier the far side answers against. A fingerprint-backed entry
is answered asynchronously, sometimes days later, carrying that identifier and nothing else that
says what it is answering.

### 5. Silence is pending, never accepted

A document the repository said nothing about stays `PENDING`. Treating silence as success claims a
criminal history entry that may still be refused.

## Consequences

- **`--cch-url` is replaced by `--exchange <file>`.** Adding a repository is shipping a jar and
  writing a file; changing what is sent to one is editing the file alone.
- **`niem replay` submits nothing**, and says so rather than staying quiet about it. Replay rebuilds
  what this platform owns; a repository is appended to, never rebuilt. That is the split of decision
  4 showing up in the operator surface.
- **`projections:cch` is deleted.** Its HTTP handling was worth keeping and its mapping logic was
  not, so `exchange:cch` carries the first and none of the second. There is no canonical type name
  anywhere in it.
- **Records are collected and assembled at the end of a run**, not streamed. An association can only
  be followed once both ends exist, and a charge streamed ahead of its arrest assembles into nothing.
- **An unresolved link is reported, never dropped.** A disposition that has not arrived yet and an
  identity that never resolved are indistinguishable from inside the assembler, so it refuses to
  decide and hands the operator both the documents and the list.
- **Field-level transformation is not yet in the exchange.** The assembly decides what is gathered;
  the writer decides how a value is spelled. A repository needing a code translated on the way out
  has nowhere to declare it, and the honest fix is the mapping layer's step vocabulary applied
  outbound rather than a second transform language. Deliberately not done here rather than
  half-done.
