# 0014. The bundled resolver is deterministic in Phase 1

**Status:** Accepted · resolves spec §10 open question 5

## Context

Spec §4.5 requires a bundled default resolver for agencies without an existing entity
resolution capability. The open question was whether it is deterministic-rules-only for
Phase 1 or probabilistic from the start.

## Decision

Deterministic rules, in tiers, each tier reporting a fixed confidence:

| Tier | Rule | Confidence |
|---|---|---|
| 1 | Driver licence number, exact after normalisation | 0.99 |
| 2 | Social Security Number, exact after normalisation | 0.99 |
| 3 | Normalised surname + given name + date of birth | 0.90 |
| — | No rule matched: new cluster | 1.00 |

Every result carries `MatchEvidence` naming the rule, the fields it matched on, and the
normalised values it compared -- so a resolution decision is explicable to an auditor without
reading the resolver's source.

## Consequences

- Acceptance criterion 3 is satisfied, and the `ResolutionProvider` contract including
  confidence and evidence is fully exercised, without building a scoring engine inside the
  slice that exists to prove the spine.
- Confidence is real but coarse. The `ResolutionAnomaly` event (§4.7) watches for shifts in the
  confidence distribution; with four discrete values, that detects tier-mix changes rather
  than genuine distribution drift. It becomes meaningful when a probabilistic resolver lands.
- A deterministic resolver under-matches: `JON SMITH` and `JONATHAN SMITH` with the same date
  of birth stay separate clusters. That is the correct failure direction for criminal justice
  -- a false merge is far worse than a missed one -- but it is a real limitation, not a
  temporary one, and agencies must be told about it.

**Revisit when:** an agency needs recall the tiers cannot give, or Phase 2 scheduling allows
probabilistic scoring.
