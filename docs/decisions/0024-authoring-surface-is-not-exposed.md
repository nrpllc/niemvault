# 0024 — The authoring surface is reachable only through the cluster's own front door

**Status:** Accepted
**Date:** 2026-09-07
**Context:** §6 (deployment), §8 (Phase 1: authentication beyond a stub is out of scope), ADR 0021

## Context

The authoring surface writes mapping and contract artifacts. Those files decide what the pipeline
does with an agency's data: which source column becomes which canonical field, which values are
quarantined, what a record's identity is derived from. Write access to them is write access to the
platform's behaviour.

Phase 1 has no authentication beyond a stub — §8 puts it explicitly out of scope, and that was the
right call for a vertical slice. But containerising the platform means deciding what happens to that
surface in a cluster, and "we will add auth later" is not a deployment posture.

The obvious shapes are all wrong. A `LoadBalancer` Service puts an unauthenticated write path on a
network. An `Ingress` does the same with a hostname attached. Even a plain `ClusterIP` Service means
anything that can reach the pod network can rewrite the mappings — and in most clusters, that is
every workload in it.

## Decision

**The server binds to `127.0.0.1` inside the pod, and the chart ships no Service, Ingress or
NodePort for it. The only way in is `kubectl port-forward`.**

```
kubectl port-forward deploy/<release>-niem-platform-authoring 8088:8088
```

The deployment is disabled by default and must be switched on deliberately.

## Why this is enforcement rather than convention

`kubectl port-forward` connects into the pod's own network namespace, so it reaches a loopback
listener. Nothing else does. That single fact does the work:

- Every access goes through the Kubernetes API server, which **authenticates** the caller,
  **authorises** it against RBAC (`pods/portforward` on that namespace), and **audits** it.
- The authentication Phase 1 does not have is supplied by the cluster — by a component that already
  exists, is already hardened, and is already the thing an agency's security team has reviewed.
- It cannot be bypassed by adding a Service later. A Service targeting a loopback listener does not
  work. Someone who disagrees with this decision has to change the server's bind address to act on
  it, which is a code change in a reviewed file rather than a values override.

The last point is what makes this worth writing down. A default that is merely *unset* gets set. A
default that is *structurally load-bearing* gets noticed.

## Consequences

- Authoring is an operator activity performed deliberately, not a web application someone leaves
  open. For Phase 1 that is the correct shape.
- Access control is expressed as RBAC on `pods/portforward`, which an agency's platform team already
  knows how to grant and revoke, rather than as a second identity system this platform would have to
  own.
- Multi-user authoring is not possible. Accepted: §10.3 (multi-tenancy) is open, and building a
  sharing model on top of a stub would be building on sand.
- When authentication arrives, this decision is revisited as a whole — it is not a matter of adding
  a Service. The bind address, the Service, and the auth model change together or not at all.
