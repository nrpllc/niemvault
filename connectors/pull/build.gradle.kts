plugins {
    id("niem.java-conventions")
}

description = "Shared machinery for pull transports (ADR 0031, ADR 0033). A remote directory, read once, acknowledged after the commit."

dependencies {
    api(project(":connectors:api"))
}
