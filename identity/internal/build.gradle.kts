plugins {
    id("niem.java-conventions")
}

description = "The bundled deterministic resolver (spec §4.5, ADR 0014), for agencies with no existing capability."

dependencies {
    api(project(":identity:api"))
}
