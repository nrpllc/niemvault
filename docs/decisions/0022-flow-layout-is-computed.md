# 0022 — Node layout is computed, never stored in the mapping

**Status:** Accepted
**Date:** 2026-09-07
**Context:** §5 (mappings are data), §7 (versioning), §4.8 (governance), ADR 0021

## Context

The authoring surface draws a hop as a field-level flow and lets an author drag nodes around. That
raises an obvious question: where do the coordinates live?

The tempting answer is "in the mapping" — either inline or in a sidecar file next to it. Every
diagramming tool that has ever shipped does one of the two, and it is what a user expects when they
move a box and come back tomorrow.

## Decision

**Layout is computed from the graph on every render. Coordinates are never written to disk.**

A drag moves a node for the session. Reloading, or pressing *Tidy layout*, returns everything to
the computed arrangement.

## Why

**A mapping is a reviewed artifact, not a document.** Mappings are versioned (§7) and changes must
be attributable (§4.8). If coordinates lived in the mapping, two mappings that transform data
identically would differ on disk because somebody dragged a box. Every such move would be a commit,
a version bump, and a diff for a reviewer to read past. The signal-to-noise ratio of the artifact's
history is the thing that makes review possible at all, and this would wreck it for no behavioural
gain.

**A sidecar file has the same problem wearing a hat.** It still has to be versioned alongside the
mapping to be useful, still diverges when the mapping changes shape, and adds a second artifact that
can go missing or go stale. A layout referring to steps that no longer exist is worse than no
layout.

**The graph is small and highly structured.** These are single-source data flows a dozen or two
nodes wide, layered by dependency depth. A computed layout is genuinely good here in a way it would
not be for a free-form diagram. There is a real answer to "where does this node go", and it is
"one column to the right of whatever produces its input".

## Consequences

- An author's arrangement does not survive a reload. Accepted: the computed layout is the one the
  next person sees too, which is the more valuable property for something reviewed by other people.
- If a flow ever grows large enough that the computed layout stops being readable, the answer is a
  better layout algorithm, not stored coordinates.
- Should stored layout ever become necessary, it must not go in the mapping artifact. That is the
  part of this decision that should outlive the rest of it.
