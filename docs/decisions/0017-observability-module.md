# 0017. Observability events live in their own module

**Status:** Accepted · deviation from the repository layout in spec §3

## Context

Spec §3 lists `core/canonical`, `core/contracts`, and `core/lineage`. It gives observability
(§4.7) no home. The two candidates were to put the event taxonomy in `core/lineage`, whose
stated job is "lineage event model and emitter", or to add a module.

Lineage answers *where did this value come from*. Observability answers *is this pipeline
still behaving*. They are emitted by the same code paths and are both structured events, which
is what makes merging them tempting, but they have different consumers, different retention
needs, and different sinks. Lineage is an audit record kept for years; a `PipelineLag` event is
operational telemetry.

More practically: `core/contracts` must depend on the observability events, because a contract
violation emits one. Putting them in `core/lineage` would make every contract validation drag
the lineage model with it.

## Decision

Add `core/observability/` holding the §4.7 event taxonomy and the emitter interface.
`core/lineage` keeps the lineage event model as §3 specifies.

## Consequences

- The §3 layout in `docs/spec.md` is now slightly out of date. That is recorded here rather
  than by editing the spec, which is an input, not a working document.
- The dependency direction is clean: `contracts` depends on `observability`, not the reverse.
- If a future phase finds lineage and observability sharing more machinery than expected, a
  shared `core/events` module is the merge point — not folding one into the other.
