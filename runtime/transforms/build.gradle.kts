plugins {
    id("niem.java-conventions")
}

description = "Transformation primitives (spec §5). Pure, serializable, and engine-agnostic."

dependencies {
    api(project(":core:canonical"))
    api(project(":core:contracts"))
}
