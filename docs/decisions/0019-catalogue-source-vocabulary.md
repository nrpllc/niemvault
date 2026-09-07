# 0019. The catalogue documents each source's own vocabulary

**Status:** Deferred to Phase 2 · decision recorded now so Phase 2 does not begin with archaeology

## Context

Spec §4.8 requires a catalogue registering every source, mapping, contract, canonical type, and
projection as an asset, from Phase 2. Jeff asked what a catalogue would document about a source
and settled the ambiguous half: alongside the structural registry, it documents **each source's
own vocabulary against canonical terms** — a business glossary, not only NIEM provenance.

The distinction matters. NIEM provenance answers *what standard does this canonical field come
from*, and the platform already records it on every type and field. A source glossary answers a
different question: *what does this agency call it, and what did they mean*. That is what makes a
mapping reviewable by a records manager rather than only by an engineer, and it is the half that
cannot be reconstructed from the data.

## Decision

The catalogue's glossary dimension records, per source:

| Source term | Meaning as the agency uses it | Canonical construct |
|---|---|---|
| `BEAT` | patrol beat / district | `Incident.beat` (platform extension) |
| `VICT` | victim | `involvementCode = VICTIM` |
| `UNK` | not recorded | absent |
| `NAME_FULL` | surname, given names, middle initial, packed | `surName` + `givenName` + `middleName` |

Not built in Phase 1. Spec §0 rule 1 is explicit that phase boundaries are deliberate, and
criteria 4 and 6 are still unproven; a catalogue over a slice that cannot yet demonstrate replay
would document an incomplete system.

## Consequences

**The build cost is lower than it looks, and falling.** Every asset the catalogue registers is
already a versioned, self-describing artifact: canonical descriptors carry NIEM provenance and
extension justifications, mappings and contracts declare their own versions and shapes, and
`ClusterAssignment` records which resolver decided what. The structural half of the catalogue is a
reader over things that already exist, not new metadata capture. `niem validate` already prints a
crude version of it.

**The glossary half is the part that decays.** A mapping artifact today encodes the
*translation* — `codeMap VICT=VICTIM`, `nullIf UNK` — but not the *meaning*. Nothing records that
`VICT` is short for victim, or that `BEAT` is a patrol district that changes on local
redistricting schedules. That knowledge exists when a mapping is authored and evaporates
afterwards; recovering it later means asking an agency what their own export meant, which is
exactly the archaeology spec §4.8 says auditors should not have to do.

**Therefore:** when the mapping and contract artifact formats are next revised, they should gain an
optional `doc:` on each declared column and step. Cheap while authoring, expensive to backfill.
That is a note for whoever revises the format, not licence to revise it now.

**Still open:** §10.1, the control plane language. Spec §2 says it blocks Phase 2 and must not be
resolved unilaterally. A catalogue with a UI runs into it immediately; a read-only catalogue
exposed through the operator CLI does not, which may be the sensible first increment.

**Revisit when:** Phase 1 acceptance is complete.
