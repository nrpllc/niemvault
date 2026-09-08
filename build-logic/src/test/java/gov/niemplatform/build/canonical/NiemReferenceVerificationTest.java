package gov.niemplatform.build.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Resolving the model's NIEM citations against a real release (ADR 0011).
 *
 * <p>Two of these cases are not hypothetical. {@code nc:PersonSexCode} and
 * {@code nc:DriverLicenseIdentification} were both asserted in the shipped model and both were
 * wrong — the first names a real element in the wrong namespace, the second names nothing at all in
 * NIEM 6.0. They are kept here as regression cases because they are precisely what an unverified
 * provenance looks like: plausible, well-formed, and false.
 */
class NiemReferenceVerificationTest {

    private static final String CORE = "https://docs.oasis-open.org/niemopen/ns/model/niem-core/6.0/";
    private static final String JUSTICE =
            "https://docs.oasis-open.org/niemopen/ns/model/domains/justice/6.0/";

    @TempDir
    Path work;

    private Path manifests;

    @BeforeEach
    void writeARelease() throws IOException {
        manifests = Files.createDirectories(work.resolve("niem"));
        Files.writeString(manifests.resolve("niem-core-6.0.manifest"), """
                # a trimmed stand-in for the real manifest
                prefix nc
                namespace %s
                T PersonType
                T ActivityPersonAssociationType
                E PersonSurName
                E PersonSexText
                E PersonLicenseIdentification
                """.formatted(CORE), StandardCharsets.UTF_8);

        Files.writeString(manifests.resolve("justice-6.0.manifest"), """
                prefix j
                namespace %s
                E PersonSexCode
                """.formatted(JUSTICE), StandardCharsets.UTF_8);
    }

    private NiemRelease release() {
        return NiemRelease.load(manifests);
    }

    /** Parses a model from YAML and validates it against the release, as the build does. */
    private void validate(String yaml) throws IOException {
        Path model = Files.createDirectories(work.resolve("canonical"));
        Files.writeString(model.resolve("person.yaml"), yaml, StandardCharsets.UTF_8);
        List<TypeDef> types = new CanonicalDslParser().parseAll(List.of(model));
        new CanonicalModelValidator(CanonicalModelTestSupport.EXT_ROOT, release()).validate(types);
    }

    /** A Person whose one field cites the given namespace and element. */
    private static String personCiting(String field, String namespace, String element) {
        return """
                type: Person
                kind: entity
                namespace: "%s"
                version: "1.0.0"
                provenance:
                  niemNamespace: "%s"
                  niemType: "nc:PersonType"
                fields:
                  - name: %s
                    type: string
                    required: true
                    provenance:
                      niemNamespace: "%s"
                      niemElement: "%s"
                """.formatted(CanonicalModelTestSupport.CORE_NS, CORE, field, namespace, element);
    }

    @Nested
    @DisplayName("Loading a release")
    class Loading {

        @Test
        @DisplayName("reads every manifest in the directory")
        void readsAllManifests() {
            assertThat(release().namespaces())
                    .extracting(NiemRelease.Namespace::prefix)
                    .containsExactlyInAnyOrder("nc", "j");
        }

        @Test
        @DisplayName("treats a namespace with and without its trailing slash as the same one")
        void toleratesTrailingSlash() {
            // NIEM publishes these with a trailing slash and they are routinely written without.
            // Treating them as different namespaces would reject correct citations.
            assertThat(release().namespace(CORE)).isPresent();
            assertThat(release().namespace(CORE.substring(0, CORE.length() - 1))).isPresent();
        }

        @Test
        @DisplayName("a missing directory is an empty release, not a crash")
        void missingDirectoryIsEmpty() {
            assertThat(NiemRelease.load(work.resolve("nothing-here")).isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("Citations that are wrong")
    class Wrong {

        @Test
        @DisplayName("the right name in the wrong namespace says where it actually lives")
        void reportsTheRightNamespace() throws IOException {
            // The real nc:PersonSexCode bug. Saying only "not found" would leave an author to
            // search several thousand names by hand; the correction is the whole value.
            assertThatThrownBy(() -> validate(personCiting("sexCode", CORE, "nc:PersonSexCode")))
                    .isInstanceOf(CanonicalDslException.class)
                    .hasMessageContaining("UNVERIFIED_NIEM_REFERENCE")
                    .hasMessageContaining("j:PersonSexCode");
        }

        @Test
        @DisplayName("a name NIEM does not have anywhere is reported as such")
        void reportsAnInventedName() throws IOException {
            // The real nc:DriverLicenseIdentification bug. NIEM 6.0 has no such element under any
            // namespace; it models this as nc:PersonLicenseIdentification.
            assertThatThrownBy(() ->
                    validate(personCiting("driverLicenseId", CORE, "nc:DriverLicenseIdentification")))
                    .isInstanceOf(CanonicalDslException.class)
                    .hasMessageContaining("no namespace in the release declares")
                    .hasMessageContaining("DriverLicenseIdentification");
        }

        @Test
        @DisplayName("a namespace the release does not carry is reported with what it does carry")
        void reportsAnUnknownNamespace() throws IOException {
            assertThatThrownBy(() -> validate(
                    personCiting("surName", "https://example.gov/not-niem/1.0/", "nc:PersonSurName")))
                    .isInstanceOf(CanonicalDslException.class)
                    .hasMessageContaining("is not in the NIEM release")
                    .hasMessageContaining(CORE);
        }
    }

    @Nested
    @DisplayName("Citations that are right")
    class Right {

        @Test
        @DisplayName("an element the namespace declares passes")
        void acceptsAVerifiedElement() throws IOException {
            validate(personCiting("surName", CORE, "nc:PersonSurName"));
        }

        @Test
        @DisplayName("a field citing another namespace than its type passes")
        void acceptsACrossNamespaceField() throws IOException {
            // A NIEM-sourced type may carry fields from a domain namespace; that is how the real
            // Person picks up j:PersonSexCode.
            validate(personCiting("sexCode", JUSTICE, "j:PersonSexCode"));
        }
    }

    @Nested
    @DisplayName("Without a release")
    class WithoutARelease {

        @Test
        @DisplayName("structural validation still runs, and citations are simply not resolved")
        void skipsVerificationRatherThanFailing() throws IOException {
            // Whether verification is mandatory is a build-level decision, enforced by the codegen
            // task. Inferring it here from an absent argument would make every unit test about the
            // DSL's own rules depend on a NIEM release.
            Path model = Files.createDirectories(work.resolve("unverified"));
            Files.writeString(model.resolve("person.yaml"),
                    personCiting("nonsense", CORE, "nc:NoSuchElementAnywhere"),
                    StandardCharsets.UTF_8);

            new CanonicalModelValidator(CanonicalModelTestSupport.EXT_ROOT)
                    .validate(new CanonicalDslParser().parseAll(List.of(model)));
        }
    }
}
