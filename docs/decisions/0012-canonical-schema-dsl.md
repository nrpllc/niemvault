# 0012. An intermediate YAML DSL is the canonical source of truth

**Status:** Accepted · resolves spec §10 open question 2

## Context

Spec §4.1 requires canonical types to be generated from a schema definition rather than
hand-written. The open question was whether that definition is NIEM XSD directly, or an
intermediate DSL that imports from NIEM.

NIEM's XSDs are large and heavily abstract. Deriving records from them directly makes
subsetting and extension handling the dominant engineering effort of Phase 1 -- work that
proves nothing about the vertical slice the phase exists to prove.

## Decision

A small YAML DSL under `src/main/canonical` is the source of truth. A Gradle plugin in
`build-logic` parses it, validates it, and generates Java records plus a runtime descriptor
carrying provenance. NIEM XSD import becomes a later importer that emits this DSL, rather than
a parallel path into codegen.

The DSL makes the §4.1 rules structural rather than advisory:

- Every type, field, and role declares NIEM provenance **or** an extension with a written
  justification. Never neither, never both. The build rejects both cases.
- Extension types live under a reserved namespace prefix, and NIEM-sourced types may not.
  Enforced in both directions.
- Two canonical types may not claim the same NIEM type. Silent redefinition is a build failure.
- Unrecognised keys are errors, because a silently ignored key is how a provenance annotation
  goes missing without anyone noticing.

A NIEM-sourced type may carry extension *fields*. That is NIEM's own augmentation pattern, and
forbidding it would push whole types into the extension namespace over one local field.

## Consequences

- Generated sources are never committed. The DSL is the versioned artifact (§0 rule 5); the
  records are a build product.
- The DSL is one more thing to maintain, and it will need to grow -- code lists, cardinality
  beyond required/repeated, and eventual NIEM import all push on it.
- Provenance is checked for *presence and consistency*, not for *correctness*. See ADR 0011.
