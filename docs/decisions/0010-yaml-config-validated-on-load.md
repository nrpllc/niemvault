# 0010. YAML config, schema-validated on load

**Status:** Accepted · pinned by spec §2 and §9

## Context

Connector configuration, mapping definitions, and contracts are all operator-editable
artifacts. An operator typo that half-configures a pipeline is worse than one that fails,
because a half-configured pipeline produces plausible output.

## Decision

YAML, validated against a schema at load time. Validation is strict: an unrecognised key is an
error, not a warning. Failures are structured, name the file and the location within it, and
report every problem in one pass rather than one per run.

The same strictness applies to the canonical DSL (ADR 0012), which is validated at build time
by exactly this rule.

## Consequences

- Fail fast and loudly. A misconfigured deployment does not start.
- Reporting all problems at once matters more than it sounds: an operator iterating one error
  per restart against a slow-starting pipeline will start guessing.
- Every config surface needs its allowed-key set declared somewhere the validator can read,
  which is a small ongoing tax paid to avoid a large silent one.
