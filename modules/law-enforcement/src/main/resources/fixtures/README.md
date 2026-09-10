# CAD reference fixtures

Sample source data, shipped as module content rather than test data (spec §3 lists `fixtures/`
alongside `mappings/` and `contracts/`). An agency onboarding a CAD source uses these to see the
shape the mapping expects before pointing it at a real export, which is the review step §4.3
describes as the valuable half of source onboarding.

`incidents.csv` is a clean export carrying the messiness a real one has: a packed `LAST, FIRST M`
name, one licence written three ways, `UNK`/`N/A`/`NONE` sentinels, an apostrophe that appears in
one row and not another, and a row with no identifying attributes at all. Ten rows, six humans,
eight incidents.

`incidents-drifted.csv` is the same source after it changed underneath the mapping: a date of
birth in ISO format, an incident timestamp in ISO format, and an added column. Every one is a
change that breaks nothing and passes every other check, which is the failure spec §4.2 exists to
catch. It is used to prove acceptance criterion 5.

## `incidents-series.csv` — what a graph is actually for

The other two fixtures are about one record at a time: does it map, and is it caught when the source
drifts. This one is about what you can only see by looking at several.

Twelve rows, six incidents, six people. Read as a spreadsheet, nothing stands out — six ordinary
calls over ten days, each with a couple of names attached. The structure is only visible once the
records are joined:

| Person | Appears on | As |
|---|---|---|
| CHEN, MEI L | 2026-000210, 2026-000237, 2026-000262 | witness, reporting party, witness |
| CHEN, MEI L | 2026-000288 | **suspect** |
| MERCER, DALE J | 2026-000237, 2026-000262 | suspect, suspect |
| WHITFIELD, TOM | 2026-000262, 2026-000301 | victim, witness |
| OKAFOR, ADA | 2026-000288, 2026-000301 | reporting party, victim |

The same person is a bystander at three burglaries in the same beat over eight days, and a suspect
in a theft on the fourth. No single record says that. No dispatcher would notice it. It is one hop
in a graph.

`MERCER` is the corroborating thread — the same suspect on two of the three burglaries — and
`WHITFIELD` and `OKAFOR` are there so the graph is not pure signal. A demonstration where every edge
is meaningful teaches the wrong lesson about what these projections look like on real data.

### The connection is invisible without identity resolution

Deliberately. Across her four appearances, CHEN's licence is written `R88-40021`, `R8840021`,
`r8840021` and `R88-40021`, and her name is `CHEN, MEI L` three times and `CHEN, MEI` once. MERCER
has no licence at all — `UNK` on one row and `N/A` on the other — so he resolves on name and date of
birth alone, which is the resolver's weaker tier and is the point of it existing.

Tidy those spellings up and the headline connection silently disappears: four records, four people,
nothing to see. `CadSeriesTest` asserts the structure for exactly that reason.

### What the platform is claiming, and what it is not

It surfaces the connection. It does not draw the conclusion. Being near three burglaries is not
evidence of anything, and a system that presented it as such would be worse than no system. What an
investigator gets is a question worth asking, with the records that raise it attached.
