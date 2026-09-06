# 0001. Java 21 and Apache Flink for bronze to silver

**Status:** Accepted · pinned by spec §2

## Context

Bronze-to-silver work is per-record: mapping, contract validation, identity resolution,
canonicalisation. It must run identically as a stream for agencies with live feeds and as a
batch for agencies that drop files nightly. Maintaining two transformation implementations
would guarantee they diverge, and divergence in a canonicalisation layer is silent corruption.

## Decision

Java 21 on Apache Flink 1.20 (LTS) for the bronze-to-silver runtime.

Flink treats batch as a bounded stream, so a single job graph runs under both
`RuntimeExecutionMode.BATCH` and `RuntimeExecutionMode.STREAMING`. That is the mechanism
behind acceptance criterion 7, which is the criterion that validates the architectural bet.

Flink embeds single-node for small agencies and scales to a cluster for federal deployments
without a code change, which the two delivery modes in §6 require.

## Consequences

- Java 21 support in Flink 1.20 is officially experimental. Flink's serialisation stack
  reflects into JDK internals, so tests and runtime both need explicit `--add-opens` flags.
  These live in the `niem.java-conventions` plugin, not scattered per module.
- Flink is weak at large shuffles and joins. Silver-to-gold work stays out of it (ADR 0002).
- Mapping definitions must compile to a job graph rather than being written as Flink code,
  or agencies could not change a mapping without a platform rebuild (spec §5).
