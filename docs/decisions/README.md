# Architecture decision records

One record per pinned decision in spec §2, plus every decision taken during implementation
(spec §9). Numbered sequentially and never renumbered; superseded records stay in place with
their status changed and a pointer to the record that replaced them.

Statuses: **Accepted** (settled, implemented) · **Proposed** (settled in principle, not yet
validated in code) · **Deferred** (decided that it is not decided yet, with the trigger for
deciding stated) · **Superseded**.

| # | Decision | Status |
|---|---|---|
| [0001](0001-java-21-and-flink.md) | Java 21 and Apache Flink for bronze to silver | Accepted |
| [0002](0002-spark-for-silver-to-gold.md) | Spark or warehouse SQL for silver to gold | Deferred |
| [0003](0003-gradle-kotlin-dsl.md) | Gradle with the Kotlin DSL | Accepted |
| [0004](0004-bronze-parquet-and-envelope.md) | Bronze as append-only Parquet with a JSON envelope | Accepted |
| [0005](0005-canonical-table-format.md) | Table format for canonical silver storage | Proposed |
| [0006](0006-neo4j-graph-projection.md) | Neo4j for the Phase 1 graph projection | Accepted |
| [0007](0007-elasticsearch-search-projection.md) | Elasticsearch for the search projection | Deferred |
| [0008](0008-warehouse-projection.md) | Warehouse projection | Deferred |
| [0009](0009-containers-and-kubernetes.md) | Containers and Kubernetes for both delivery modes | Accepted |
| [0010](0010-yaml-config-validated-on-load.md) | YAML config, schema-validated on load | Accepted |
| [0011](0011-niem-reference-verification.md) | NIEM references are asserted, not yet verified | Proposed |
| [0012](0012-canonical-schema-dsl.md) | An intermediate YAML DSL is the canonical source of truth | Accepted |
| [0013](0013-phase1-reference-source.md) | Synthetic CAD CSV as the Phase 1 reference source | Accepted |
| [0014](0014-deterministic-default-resolver.md) | The bundled resolver is deterministic in Phase 1 | Accepted |
| [0015](0015-records-redact-values.md) | Record `toString` never exposes values | Accepted |
| [0016](0016-single-tenant-phase1.md) | Phase 1 assumes one tenant per deployment | Proposed |
