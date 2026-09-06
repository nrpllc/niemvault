import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

// Root build. Deliberately thin: every module opts into behaviour through the convention
// plugins in build-logic rather than inheriting it from an allprojects/subprojects block,
// so a module's build file states what that module actually is.

tasks.register("canonicalModel") {
    group = "canonical model"
    description = "Validates and regenerates every canonical model in the build."
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("generateCanonicalTypes") })
}

/**
 * Merges every module's JUnit XML into a single `.zen/test-report.xml`.
 *
 * The Zen sidecar reads a JUnit report from `.zen/` to populate its Tests panel, but does not
 * detect Gradle as a runner and would otherwise report this project as having no tests at all
 * -- which is indistinguishable from a broken integration. Gradle already writes JUnit XML per
 * module; this only gathers it into the one file the sidecar looks for.
 */
val zenTestReport by tasks.registering {
    group = "verification"
    description = "Aggregates JUnit XML from every module into .zen/test-report.xml for the Zen sidecar."

    val resultDirs = (subprojects.map { it.layout.buildDirectory.dir("test-results").get().asFile }
            + layout.projectDirectory.dir("build-logic/build/test-results").asFile)
    val outputFile = layout.projectDirectory.file(".zen/test-report.xml").asFile

    inputs.files(resultDirs.map { fileTree(it) { include("**/TEST-*.xml") } })
    outputs.file(outputFile)

    doLast {
        val suiteFiles = resultDirs
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.name.startsWith("TEST-") && it.extension == "xml" } }
            .sortedBy { it.absolutePath }

        if (suiteFiles.isEmpty()) {
            logger.lifecycle("zenTestReport: no JUnit XML found -- run `gradlew test` first.")
            return@doLast
        }

        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder()
        val merged = builder.newDocument()
        val root = merged.createElement("testsuites")
        merged.appendChild(root)

        var tests = 0
        var failures = 0
        var errors = 0
        var skipped = 0

        suiteFiles.forEach { file ->
            val suite = builder.parse(file).documentElement
            tests += suite.getAttribute("tests").toIntOrNull() ?: 0
            failures += suite.getAttribute("failures").toIntOrNull() ?: 0
            errors += suite.getAttribute("errors").toIntOrNull() ?: 0
            skipped += suite.getAttribute("skipped").toIntOrNull() ?: 0
            root.appendChild(merged.importNode(suite, true))
        }

        root.setAttribute("tests", tests.toString())
        root.setAttribute("failures", failures.toString())
        root.setAttribute("errors", errors.toString())
        root.setAttribute("skipped", skipped.toString())

        outputFile.parentFile.mkdirs()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes")
        }.transform(DOMSource(merged), StreamResult(outputFile))

        logger.lifecycle(
            "zenTestReport: {} suite file(s), {} tests, {} failing, {} skipped -> {}",
            suiteFiles.size, tests, failures + errors, skipped, outputFile
        )
    }
}

/** `gradlew zenTest` runs the suite and leaves a report where the Zen sidecar can find it. */
tasks.register("zenTest") {
    group = "verification"
    description = "Runs every test and aggregates the results for the Zen sidecar."
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("test") })
    finalizedBy(zenTestReport)
}
