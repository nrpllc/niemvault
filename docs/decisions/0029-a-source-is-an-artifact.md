# 0029 — A source is a versioned artifact, not a set of command-line flags

**Status:** Accepted
**Date:** 2026-09-09
**Context:** §0.5 (everything is a versioned artifact), §4.3 (connectors), §9 (config validated on load)

## Context

`niem run` knew how to configure exactly one connector. It took `--drop`, `--pattern` and
`--skip-header-lines`, and built a `FileDropConnector` from them by name.

That is fine with one transport and wrong with two. A Kafka source needs a broker, a topic, a
consumer group and a retention posture. A CDC source will need a connection, a table list and a log
position. An FTP source will need a host, a path, a credential and an archive policy. None of those
belongs on a command line shared with all the others, and the shape of that command line would
otherwise grow by one transport's worth of flags per transport — while `ConnectorRegistry` sat there
being a service-loader SPI that nothing asked.

Spec §0.5 already says what the answer is: every mapping, schema and transformation is a versioned
artifact on disk. A source's transport configuration is no different, and it was the one piece of
platform configuration living in an operator's shell history instead.

## Decision

A source is described by a YAML artifact naming its transport and that transport's settings. The
connector registry resolves the transport; the connector validates its own settings.

```yaml
sourceId: riverton-pd-cad
connectorInstanceId: cad-kafka-1
type: kafka
freshnessSla: PT15M
settings:
  bootstrapServers: broker:9092
  topic: cad.incidents
  groupId: niem-ingest-riverton
  retention: retained
```

Three properties follow, and each was a reason:

1. **Adding a transport changes no command.** Shipping a jar and writing a file is what §4.3 promised
   when it made connectors discoverable. Until now it was true of the runtime and false of the
   operator surface.
2. **Transport configuration is reviewable and diffable.** "Which group id is production reading
   with?" is answerable from the repository rather than from a running pod's arguments.
3. **It is strict on load** (ADR 0010). An unrecognised key is an error, because a silently ignored
   `retention` is how a topic carrying non-retainable responses gets landed anyway.

`--drop` stays as a shorthand that builds a file-drop definition. One transport is worth a
convenience, and it keeps a one-line demonstration a one-line demonstration. Both routes construct
the same `SourceDefinition` and meet before anything is landed, so there is one code path from there
down rather than two that could drift.

### The definition's source must match the mapping's

A mapping is written against a named source. Landing a different one under it would map cleanly and
produce well-formed canonical records about the wrong feed — which no contract catches, because
every record is valid. So the two are checked against each other before anything is opened.

## Consequences

- **`SourceDefinition` carries transport only**, the same boundary `ConnectorConfig` draws. No field
  mappings, no canonical types. The Kafka and file-drop definitions for Riverton CAD sit beside each
  other in the module and share one mapping, which is the separation §4.3 describes made visible on
  disk.
- **Settings print as keys, never values.** A settings map routinely holds a credential, so
  `SourceDefinition.toString()` carries the same obligation as `Record.toString()` (ADR 0015).
- **A missing connector says what the deployment does have.** In an air-gapped install the operator
  needs to know whether they are missing a jar or have misspelled a transport, and only the list
  distinguishes them.
- **Not yet covered by `niem validate`.** A source definition is validated when `run` loads it, which
  is fail-fast but later than it could be. Validating it alongside mappings and contracts is the
  obvious next step and is deliberately not done here rather than half-done.
