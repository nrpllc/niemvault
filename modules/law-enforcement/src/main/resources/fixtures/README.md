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
