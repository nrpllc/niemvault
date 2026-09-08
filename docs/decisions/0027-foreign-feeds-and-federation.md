# 0027 — Two kinds of boundary, one path into canonical

**Status:** Accepted
**Date:** 2026-09-07
**Context:** §4.3 (connectors), §4.2 (hop contracts), §4.4 (bronze), ADR 0026

## Context

A city police department will need criminal history it does not hold. It comes from a state system,
or a federal one, and the platform does not get to dictate the terms: not the protocol, not the
schema, not the interaction model, and not whether the other end speaks NIEM at all.

Jeff's framing: we cannot assume whether such a feed is queryable or a bulk sink, and we may not be
able to dictate NIEM or a variation of it. But NIEM remains our canonical form. Federation is worth
supporting, and live query may have to be flexible configuration, because the integrations are many
and messy. Keeping our own house in order and offering a standard interface for like tenants is the
goal.

The risk in that is specific: flexibility introduced for the messy half leaking into the strict half,
until "we could not dictate their schema" becomes "the canonical model bends to whatever arrived."

## Decision

### 1. Two classes of boundary, never conflated

**Foreign integration** — a state, federal, or vendor system. Arbitrary protocol, arbitrary schema,
arbitrary interaction model. Nothing is assumed and everything is configuration. This is where the
mess lives, deliberately and by itself.

**Federation** — between tenants running this platform. One interface, which we define, speaking the
canonical model and NIEM. No negotiation and no per-partner variation, because both ends are ours.

Separating them is what stops the first from eroding the second. An integration that cannot be made
to fit federation is a foreign integration; it does not become a dialect of federation.

### 2. NIEM is the canonical form and is not negotiated at the boundary

A foreign system may speak NIEM, a local variation of it, or something unrelated. That is a mapping
problem, and mapping is what this platform is for. It is never a reason to alter the canonical model,
add a field the model does not have, or accept a record that did not pass a hop contract.

### 3. Interaction mode is a property of a connector, not a second pipeline

Push, poll, and live query are how records arrive. What happens next is identical: contract-gated
hops into canonical, exactly as a file drop does today.

This is the rule that matters most. Two ways into canonical means one of them is not
contract-validated, and given what is being integrated, it would be the one carrying criminal history
from a state system. A live-query result is not a shortcut past the gate; it is a record that arrived
by a different means.

### 4. Retention posture is declared, and it may forbid landing

This does not follow from architecture. It follows from law, and it is the reason "everything lands
in bronze" cannot be universal.

Responses from criminal history systems are frequently **not retainable**. The rules governing III
and comparable systems commonly permit use for the purpose at hand and forbid keeping a copy. A
platform whose landing zone is append-only and permanent would, by doing the obviously correct thing,
create an unlawful retention.

So a connector declares one of:

- **`RETAINED`** — records land in bronze and follow the normal path. Replayable, because bronze
  holds what arrived.
- **`TRANSIENT`** — records must not be retained. They are used for the request at hand and never
  landed. The only durable trace is the disclosure record: that we asked, under what authority, and
  how much came back — never the content.

The disclosure log (ADR 0026) is exactly the right artifact for this, and that is not a coincidence:
the thing you are permitted to keep about a non-retainable response is precisely the fact that you
requested it.

### 5. Asking is a disclosure too

Querying a state system for criminal history is a disclosure, in the inbound direction. It is
recorded in this agency's log with the same fields as an outbound one — `DisclosureRecord` is already
symmetric about which tenant asked and which responded.

## Consequences

- **A transient feed cannot be replayed, and must not appear to be.** Criterion 6 rests on bronze
  holding what arrived. Where nothing may be held, replay is not available, and any surface that
  offers it for such a source is lying. This has to be visible in the catalogue and refused by the
  replay command rather than silently producing less.
- **Contract validation applies identically to a transient feed.** Not landing is not the same as not
  being checked. A record that fails its contract is quarantined whether or not it may be kept —
  though the quarantined copy of a non-retainable record is itself a retention, so a transient
  source's quarantine holds the violation and the shape, not the record.
- **Federation stays small on purpose.** Every accommodation added to it is one every future partner
  inherits. Foreign adapters absorb variation; federation does not.
- **Configuration is bounded by contracts, not by trust.** A foreign adapter may be configured
  freely, and whatever it produces still passes a hop contract before it is canonical. That is what
  makes "flexible configuration" safe to offer.
- **Still open:** whether a transient response may be cached for the life of a single investigation,
  and by what authority. That is a question for counsel per jurisdiction, not an architecture
  decision, and the platform should be able to express either answer rather than assume one.
