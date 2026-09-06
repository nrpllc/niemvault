# 0013. Synthetic CAD CSV as the Phase 1 reference source

**Status:** Accepted · resolves spec §10 open question 4

## Context

Phase 1 needs one law enforcement source with realistic fixtures. The options were a CAD
export, an RMS export, or a NIEM IEPD XML sample.

A NIEM IEPD would be the least work to map -- which makes it the weakest test, since the
mapping layer is the thing Phase 1 exists to prove. A source already in canonical shape
exercises almost nothing.

## Decision

A synthetic CAD export in CSV: `incidents.csv` and `persons.csv`, joined on incident number.
Authored by us, carrying the messiness real CAD exports have -- name packed into one field in
`LAST, FIRST M` form, inconsistent date formats, sentinel values standing in for nulls,
inconsistent casing and whitespace.

Synthetic rather than real: no licensing constraint, no data sensitivity, and fixtures can be
constructed to exercise specific behaviours -- including the deliberately corrupted record
acceptance criterion 5 requires.

## Consequences

- The fixtures must stay genuinely messy. A tidy fixture would let a mapping pass that could
  not survive a real export, which would make Phase 1 a false positive.
- CSV means the flattening work in the mapping DAG is modest. An RMS-shaped nested JSON source
  would exercise it harder, and is the natural second source in Phase 2.
- "Which vendor's export shape" remains unanswered and is deliberately not invented here. The
  fixtures follow the common shape of CAD exports rather than claiming to be any vendor's.
