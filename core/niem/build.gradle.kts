plugins {
    id("niem.java-conventions")
}

description = "The NIEM release manifest format and its parser (§4.1, ADR 0011)."

// No dependencies, deliberately. This file is also compiled into build-logic, where the only
// things on the classpath are the Gradle API and SnakeYAML; anything added here would have to
// exist there too, and a verification parser that can be broken by a library upgrade is not one
// the build should rest on.
