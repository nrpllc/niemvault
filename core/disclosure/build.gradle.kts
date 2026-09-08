plugins {
    id("niem.java-conventions")
}

description = "The record of what crossed an agency boundary (spec §4.8, ADR 0026)."

dependencies {
    // Disclosures name canonical identities and the agencies either side of them. They never carry
    // record values, so nothing here depends on the record types.
    api(project(":core:canonical"))
}
