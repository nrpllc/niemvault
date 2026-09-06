plugins {
    id("niem.java-conventions")
    id("niem.canonical-codegen")
}

description = "NIEM-derived canonical model: DSL sources, generated types, and the generic record form."

canonicalModel {
    targetPackage.set("gov.niemplatform.canonical.core")
    catalogueClassName.set("CoreCanonicalTypes")

    // Spec §4.1: extensions live in a namespace separate from NIEM-sourced types. The build
    // fails if an extension escapes this prefix, or a NIEM-sourced type strays into it.
    extensionNamespaceRoot.set("https://niemplatform.gov/canonical/extension/")
}
