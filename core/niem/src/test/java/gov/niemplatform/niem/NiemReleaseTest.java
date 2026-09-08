package gov.niemplatform.niem;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("A NIEM release manifest")
class NiemReleaseTest {

    private static final String MARITIME = """
            # NIEM 6.0 release manifest -- maritime
            # source: https://example.invalid/maritime.xsd
            prefix m
            namespace https://docs.oasis-open.org/niemopen/ns/model/domains/maritime/6.0/
            T VesselType
            T VoyageType
            E Vessel
            """;

    @Nested
    @DisplayName("read from a directory")
    class FromDirectory {

        @Test
        @DisplayName("carries its prefix, namespace and declarations")
        void parsesAManifest(@TempDir Path directory) throws Exception {
            Files.writeString(directory.resolve("maritime-6.0.manifest"), MARITIME);

            NiemRelease release = NiemRelease.load(directory);
            NiemRelease.Namespace maritime = release.namespaces().getFirst();

            assertThat(maritime.prefix()).isEqualTo("m");
            assertThat(maritime.types()).containsExactlyInAnyOrder("VesselType", "VoyageType");
            assertThat(maritime.elements()).containsExactly("Vessel");
            assertThat(maritime.declarationCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("takes its domain name from the file, so the two cannot disagree")
        void namesTheDomainAfterTheFile(@TempDir Path directory) throws Exception {
            Files.writeString(directory.resolve("maritime-6.0.manifest"), MARITIME);

            assertThat(NiemRelease.load(directory).namespaces().getFirst().name()).isEqualTo("maritime");
        }

        @Test
        @DisplayName("resolves a namespace whether or not the citation has the trailing slash")
        void toleratesTheTrailingSlash(@TempDir Path directory) throws Exception {
            // NIEM publishes these with a trailing slash and authors routinely write them without.
            // Treating those as different namespaces would fail correct references.
            Files.writeString(directory.resolve("maritime-6.0.manifest"), MARITIME);
            NiemRelease release = NiemRelease.load(directory);

            String withSlash = "https://docs.oasis-open.org/niemopen/ns/model/domains/maritime/6.0/";
            assertThat(release.namespace(withSlash)).isPresent();
            assertThat(release.namespace(withSlash.substring(0, withSlash.length() - 1))).isPresent();
        }

        @Test
        @DisplayName("is empty rather than failing when there is no release yet")
        void missingDirectoryIsAnEmptyRelease(@TempDir Path directory) {
            // The model has to stay buildable while a release is being obtained. The validator
            // reports the resulting unverifiability itself rather than the loader throwing here.
            assertThat(NiemRelease.load(directory.resolve("absent")).isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("read from the classpath")
    class FromClasspath {

        @Test
        @DisplayName("loads every manifest the index names")
        void loadsWhatTheIndexNames() {
            NiemRelease release = NiemRelease.fromClasspath(
                    loaderFor(Map.of(
                            "niem/manifests.index", "# a comment\n\nmaritime-6.0.manifest\n",
                            "niem/maritime-6.0.manifest", MARITIME)),
                    "niem");

            assertThat(release.namespaces()).singleElement()
                    .extracting(NiemRelease.Namespace::prefix).isEqualTo("m");
        }

        @Test
        @DisplayName("is empty when the platform ships no release")
        void noIndexIsAnEmptyRelease() {
            assertThat(NiemRelease.fromClasspath(loaderFor(Map.of()), "niem").isEmpty()).isTrue();
        }
    }

    @Test
    @DisplayName("says where a name is actually declared, so a wrong namespace can be corrected")
    void suggestsTheRightNamespace(@TempDir Path directory) throws Exception {
        // The message that matters: nc:PersonSexCode does not exist, j: declares PersonSexCode.
        // "Not found" alone leaves an author to search several thousand names by hand.
        Files.writeString(directory.resolve("maritime-6.0.manifest"), MARITIME);

        assertThat(NiemRelease.load(directory).whereDeclared("VesselType", true))
                .containsExactly("m:VesselType");
        assertThat(NiemRelease.load(directory).whereDeclared("VesselType", false)).isEmpty();
    }

    /** A classloader over an in-memory set of resources, so the test needs no packaged release. */
    private static ClassLoader loaderFor(Map<String, String> resources) {
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                String content = resources.get(name);
                return content == null
                        ? null
                        : new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public Enumeration<URL> getResources(String name) {
                return java.util.Collections.emptyEnumeration();
            }
        };
    }
}
