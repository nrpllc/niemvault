# 0009. Containers and Kubernetes for both delivery modes

**Status:** Accepted · pinned by spec §2 and §6

## Context

Two delivery modes: managed updates pushed from a vendor cloud, and an installable package for
air-gapped environments. Many criminal justice environments will not permit outbound
connectivity, so air-gapped is mandatory rather than a fallback.

## Decision

Everything is containerised and declarative, delivered as Kubernetes manifests and a Helm
chart. One artifact serves both modes; the difference is how it is delivered, not what it is.

There is no separate on-prem codebase and no on-prem-only code path. A feature that cannot
work air-gapped is designed wrong and is rejected on that basis, not accommodated.

## Consequences

- No component may require outbound network access at runtime. That rules out phone-home
  licensing, hosted-only telemetry sinks, and any dependency resolved at deploy time.
- Container images must be self-contained down to the last dependency, and the air-gapped
  package must include every image rather than referencing a registry.
- The observability events of §4.7 must have a sink that works with no external service --
  which is the concrete reason drift detection is built in rather than bought.
