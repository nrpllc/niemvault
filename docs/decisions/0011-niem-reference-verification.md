# 0011. NIEM references are asserted, not yet verified

**Status:** Proposed · known gap, raised during Phase 1 implementation

## Context

Every canonical type and field declares NIEM provenance (§4.1). The value of that provenance
depends entirely on it being correct. A canonical field citing `nc:PersonSurName` is a
liability rather than an asset if the real NIEM element is named something else -- downstream
consumers would trust an attribution nobody checked.

No NIEM 6.0 release is present in this repository, so the provenance references written during
Phase 1 implementation are asserted from knowledge, not validated against the standard.

## Decision

Provenance references are written as the best available assertion and **explicitly marked as
unverified**, in the DSL sources and here. Where a NIEM construct could not be cited with
confidence, the construct is modelled as an extension with a justification saying so, rather
than given a guessed NIEM reference. `PersonIncidentAssociation` is the case in point: NIEM
plainly has person-to-activity association machinery, but asserting a type name we have not
confirmed would be precisely the silent misattribution the provenance rule exists to prevent.

## Consequences

- **Phase 1 cannot be signed off on this basis alone.** A NIEM 6.0 release must be obtained
  and every reference checked against it.
- The check should be mechanical, not a review: a build-time validator that resolves every
  `niemNamespace`/`niemType`/`niemElement` triple against a NIEM release manifest, failing the
  build on an unresolvable reference. That validator is not built yet.
- Once verified, `PersonIncidentAssociation` is expected to be re-based from an extension onto
  its NIEM association type. That is a provenance change and a content version bump, not a
  structural change -- the roles-not-foreign-keys shape already follows NIEM's pattern.

**Revisit when:** a NIEM 6.0 release is available to validate against.
