plugins {
    id("niem.java-conventions")
}

description = "File drop connector (spec §4.3). The only connector in Phase 1."

dependencies {
    api(project(":connectors:api"))
}
