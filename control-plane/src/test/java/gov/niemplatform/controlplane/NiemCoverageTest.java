package gov.niemplatform.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.canonical.core.CoreCanonicalTypes;
import gov.niemplatform.canonical.meta.CanonicalFieldDescriptor;
import gov.niemplatform.canonical.meta.CanonicalKind;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.canonical.meta.ExtensionJustification;
import gov.niemplatform.canonical.meta.FieldType;
import gov.niemplatform.canonical.meta.NiemProvenance;
import gov.niemplatform.niem.NiemRelease;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("NIEM coverage")
class NiemCoverageTest {

    private static final String NC_URI = "https://docs.oasis-open.org/niemopen/ns/model/niem-core/6.0/";

    private static NiemRelease releaseIn(Path directory) throws Exception {
        Files.writeString(directory.resolve("niem-core-6.0.manifest"), """
                prefix nc
                namespace %s
                T PersonType
                T ActivityType
                E PersonBirthDate
                E PersonGivenName
                """.formatted(NC_URI));
        Files.writeString(directory.resolve("maritime-6.0.manifest"), """
                prefix m
                namespace https://docs.oasis-open.org/niemopen/ns/model/domains/maritime/6.0/
                T VesselType
                E Vessel
                """);
        return NiemRelease.load(directory);
    }

    private static CanonicalTypeDescriptor person() {
        return new CanonicalTypeDescriptor(
                "Person",
                "https://niemplatform.gov/canonical/core/",
                "1.0.0",
                CanonicalKind.ENTITY,
                new NiemProvenance(NC_URI, "PersonType", null),
                null,
                List.of(
                        new CanonicalFieldDescriptor("birthDate", FieldType.DATE, false, false,
                                new NiemProvenance(NC_URI, null, "PersonBirthDate"), null, List.of(), null),
                        new CanonicalFieldDescriptor("riskScore", FieldType.STRING, false, false,
                                null, new ExtensionJustification("No NIEM element carries a locally computed score."),
                                List.of(), null)),
                List.of());
    }

    @Nested
    @DisplayName("over the model")
    class OverTheModel {

        @Test
        @DisplayName("files each citation under the namespace it names")
        void countsCitations(@TempDir Path directory) throws Exception {
            NiemCoverage.Report report = NiemCoverage.of(releaseIn(directory), List.of(person()));

            NiemCoverage.Namespace core = report.namespaces().stream()
                    .filter(namespace -> namespace.prefix().equals("nc")).findFirst().orElseThrow();

            assertThat(core.citedTypes()).extracting(NiemCoverage.Citation::niemName)
                    .containsExactly("PersonType");
            assertThat(core.citedElements()).extracting(NiemCoverage.Citation::where)
                    .containsExactly("Person.birthDate");
            assertThat(core.declared()).isEqualTo(4);
            assertThat(core.cited()).isEqualTo(2);
        }

        @Test
        @DisplayName("reports a namespace the model never goes near, rather than omitting it")
        void keepsUntouchedNamespaces(@TempDir Path directory) throws Exception {
            // The untouched domains are the point of the report as much as the used ones: the
            // question is how much of NIEM this covers, and that needs the denominator visible.
            NiemCoverage.Report report = NiemCoverage.of(releaseIn(directory), List.of(person()));

            NiemCoverage.Namespace maritime = report.namespaces().stream()
                    .filter(namespace -> namespace.prefix().equals("m")).findFirst().orElseThrow();

            assertThat(maritime.touched()).isFalse();
            assertThat(maritime.declared()).isEqualTo(2);
        }

        @Test
        @DisplayName("orders the namespaces the model stands on first")
        void ordersUsedFirst(@TempDir Path directory) throws Exception {
            NiemCoverage.Report report = NiemCoverage.of(releaseIn(directory), List.of(person()));

            assertThat(report.namespaces()).first()
                    .extracting(NiemCoverage.Namespace::prefix).isEqualTo("nc");
            assertThat(report.namespacesTouched()).isEqualTo(1);
        }

        @Test
        @DisplayName("carries an extension's written reason, not just the fact of it")
        void reportsExtensionsWithTheirJustification(@TempDir Path directory) throws Exception {
            // An extension is a decision, not a gap. Listing only what was cited would present the
            // platform's deliberate deviations as omissions.
            NiemCoverage.Report report = NiemCoverage.of(releaseIn(directory), List.of(person()));

            assertThat(report.extensions()).singleElement().satisfies(extension -> {
                assertThat(extension.where()).isEqualTo("Person.riskScore");
                assertThat(extension.justification()).contains("locally computed score");
            });
        }
    }

    @Nested
    @DisplayName("when a citation does not resolve")
    class Unresolved {

        @Test
        @DisplayName("reports an unknown namespace rather than dropping the citation")
        void reportsAnUnknownNamespace(@TempDir Path directory) throws Exception {
            CanonicalTypeDescriptor stray = new CanonicalTypeDescriptor(
                    "Vessel", "https://niemplatform.gov/canonical/core/", "1.0.0", CanonicalKind.ENTITY,
                    new NiemProvenance("https://example.invalid/nowhere/", "VesselType", null),
                    null, List.of(), List.of());

            NiemCoverage.Report report = NiemCoverage.of(releaseIn(directory), List.of(stray));

            assertThat(report.unresolved()).singleElement().satisfies(problem -> {
                assertThat(problem.where()).isEqualTo("Vessel");
                assertThat(problem.reason()).contains("no such namespace");
            });
            assertThat(report.cited()).isZero();
        }

        @Test
        @DisplayName("reports a name the namespace does not declare")
        void reportsAnUndeclaredName(@TempDir Path directory) throws Exception {
            CanonicalTypeDescriptor stray = new CanonicalTypeDescriptor(
                    "Person", "https://niemplatform.gov/canonical/core/", "1.0.0", CanonicalKind.ENTITY,
                    new NiemProvenance(NC_URI, "NoSuchType", null), null, List.of(), List.of());

            NiemCoverage.Report report = NiemCoverage.of(releaseIn(directory), List.of(stray));

            assertThat(report.unresolved()).singleElement()
                    .extracting(NiemCoverage.Unresolved::reason)
                    .satisfies(reason -> assertThat(reason).asString().contains("declares no such type"));
        }
    }

    @Nested
    @DisplayName("against the packaged release")
    class PackagedRelease {

        @Test
        @DisplayName("loads the whole NIEM 6.0 model from the classpath")
        void loadsFromTheClasspath() {
            // The manifests are resources of core:canonical, so they travel with the jar. If this
            // fails in a deployment it fails here first.
            NiemRelease release = NiemRelease.fromClasspath();

            assertThat(release.isEmpty()).isFalse();
            assertThat(release.namespaces()).hasSize(18);
            assertThat(release.namespaces()).extracting(NiemRelease.Namespace::prefix)
                    .contains("nc", "j", "m", "im", "scr");
        }

        @Test
        @DisplayName("resolves every citation the real model makes")
        void theRealModelResolvesCompletely() {
            // The build refuses a provenance that does not resolve (ADR 0011), so this must hold.
            // Asserting it here is what would catch the runtime release drifting from the build's.
            NiemCoverage.Report report =
                    NiemCoverage.of(NiemRelease.fromClasspath(), CoreCanonicalTypes.ALL);

            assertThat(report.unresolved()).isEmpty();
            assertThat(report.cited()).isPositive();
            assertThat(report.namespacesTouched()).isPositive();
        }

        @Test
        @DisplayName("every extension the real model declares carries a written reason")
        void everyExtensionIsJustified() {
            NiemCoverage.Report report =
                    NiemCoverage.of(NiemRelease.fromClasspath(), CoreCanonicalTypes.ALL);

            assertThat(report.extensions())
                    .allSatisfy(extension ->
                            assertThat(extension.justification()).isNotBlank());
        }
    }
}
