package gov.niemplatform.runtime.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.canonical.core.Incident;
import gov.niemplatform.canonical.core.Person;
import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.contracts.QuarantineSink;
import gov.niemplatform.identity.api.InMemoryClusterIndex;
import gov.niemplatform.identity.internal.DeterministicResolutionProvider;
import gov.niemplatform.observability.ObservabilityEmitter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Mappings loaded from artifacts on disk (spec §5: mappings are data, not code).
 *
 * <p>The point of the loader is that an agency can change what the pipeline produces by editing a
 * file. The test that matters most is therefore the one at the bottom: the real artifact, loaded
 * from disk, produces the same canonical output as the hand-built definition the rest of the
 * engine tests use.
 */
class MappingLoaderTest {

    @TempDir
    Path dir;

    private static final String ARTIFACT = "/mappings/cad-to-canonical-1.0.0.yaml";

    private static MappingDefinition loadShipped() {
        try (InputStream artifact = MappingLoaderTest.class.getResourceAsStream(ARTIFACT)) {
            return new MappingLoader().load(artifact, "cad-to-canonical-1.0.0.yaml");
        } catch (IOException e) {
            throw new AssertionError("the shipped mapping artifact is unreadable", e);
        }
    }

    private static String shippedText() {
        try (InputStream artifact = MappingLoaderTest.class.getResourceAsStream(ARTIFACT)) {
            return new String(artifact.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("the shipped mapping artifact is unreadable", e);
        }
    }

    private MappingDefinition loadModified(String find, String replace) throws IOException {
        Path file = dir.resolve("mapping.yaml");
        Files.writeString(file, shippedText().replace(find, replace), StandardCharsets.UTF_8);
        return new MappingLoader().load(file);
    }

    private static void assertFailsWith(ThrowingCallable action, MappingLoadException.Code expected) {
        assertThatThrownBy(action)
                .isInstanceOf(MappingLoadException.class)
                .satisfies(thrown -> assertThat(((MappingLoadException) thrown).problems())
                        .extracting(MappingLoadException.Problem::code)
                        .contains(expected));
    }

    @Test
    @DisplayName("the shipped artifact loads into a complete mapping graph")
    void loadsShippedArtifact() {
        MappingDefinition mapping = loadShipped();

        assertThat(mapping.qualifiedName()).isEqualTo("cad-to-canonical@1.0.0");
        assertThat(mapping.sourceId()).isEqualTo("riverton-pd-cad");
        assertThat(mapping.decoder().columns()).hasSize(10).startsWith("INC_NUM");
        assertThat(mapping.hops()).extracting(HopDefinition::hopId)
                .containsExactly("map-incident", "map-person", "map-person-incident");
    }

    @Test
    @DisplayName("hops come back in dependency order, with the association last")
    void dependencyOrderIsRespected() {
        assertThat(loadShipped().hopsInDependencyOrder())
                .extracting(HopDefinition::hopId)
                .last().isEqualTo("map-person-incident");
    }

    @Test
    @DisplayName("both identity modes survive the round trip")
    void identityModesLoad() {
        MappingDefinition mapping = loadShipped();

        HopDefinition person = mapping.hops().get(1);
        assertThat(person.identity().mode()).isEqualTo(IdentitySpec.Mode.RESOLVE);
        assertThat(person.identity().providerId()).isEqualTo("bundled-deterministic");
        assertThat(person.scratch()).containsExactly("givenNames");

        HopDefinition incident = mapping.hops().getFirst();
        assertThat(incident.identity().mode()).isEqualTo(IdentitySpec.Mode.DERIVE);
        assertThat(incident.identity().prefix()).isEqualTo("INC/RIVERTON-PD/");
    }

    @Nested
    @DisplayName("validated on load, not on the first record")
    class Validation {

        @Test
        @DisplayName("a misspelled key is rejected rather than ignored")
        void misspelledKeyRejected() throws IOException {
            assertFailsWith(() -> loadModified("    scratch: [givenNames]", "    scrach: [givenNames]"),
                    MappingLoadException.Code.UNKNOWN_KEY);
        }

        @Test
        @DisplayName("an unknown transform type fails the deployment")
        void unknownTransformRejected() throws IOException {
            assertFailsWith(() -> loadModified("type: copy", "type: teleport"),
                    MappingLoadException.Code.UNKNOWN_TRANSFORM);
        }

        @Test
        @DisplayName("a bad regex fails on load, not on the first record that hits it")
        void badRegexRejected() throws IOException {
            assertFailsWith(() -> loadModified("pattern: \"[^A-Za-z0-9]\"", "pattern: \"[unclosed\""),
                    MappingLoadException.Code.UNKNOWN_TRANSFORM);
        }

        @Test
        @DisplayName("parseDateTime without a zone is rejected, since guessing shifts every incident")
        void missingZoneRejected() throws IOException {
            assertFailsWith(() -> loadModified("          zone: \"America/Denver\"\n", ""),
                    MappingLoadException.Code.UNKNOWN_TRANSFORM);
        }

        @Test
        @DisplayName("a scratch field no step writes is a typo, and is rejected")
        void danglingScratchRejected() throws IOException {
            assertFailsWith(() -> loadModified("scratch: [givenNames]", "scratch: [givenNamez]"),
                    MappingLoadException.Code.UNDECLARED_TARGET);
        }

        @Test
        @DisplayName("a hop depending on a hop that does not exist is rejected")
        void danglingDependencyRejected() throws IOException {
            assertFailsWith(() -> loadModified("dependsOn: [map-person, map-incident]",
                    "dependsOn: [map-person, map-vehicle]"),
                    MappingLoadException.Code.GRAPH);
        }

        @Test
        @DisplayName("declared columns are required, because a header row must not be trusted")
        void columnsAreRequired() throws IOException {
            String text = shippedText();
            int start = text.indexOf("  columns:");
            int end = text.indexOf("hops:");
            Path file = dir.resolve("mapping.yaml");
            Files.writeString(file, text.substring(0, start) + "\n" + text.substring(end),
                    StandardCharsets.UTF_8);

            assertFailsWith(() -> new MappingLoader().load(file), MappingLoadException.Code.MISSING_KEY);
        }

        @Test
        @DisplayName("every problem is reported in one pass")
        void allProblemsAtOnce() throws IOException {
            Path file = dir.resolve("mapping.yaml");
            Files.writeString(file, shippedText()
                    .replace("type: copy", "type: teleport")
                    .replace("scratch: [givenNames]", "scrach: [givenNames]"), StandardCharsets.UTF_8);

            assertThatThrownBy(() -> new MappingLoader().load(file))
                    .isInstanceOf(MappingLoadException.class)
                    .satisfies(thrown -> assertThat(((MappingLoadException) thrown).problems())
                            .hasSizeGreaterThanOrEqualTo(2));
        }
    }

    @Test
    @DisplayName("the artifact on disk produces the same canonical output as the code-built mapping")
    void artifactMatchesTheHandBuiltDefinition() {
        List<Record> fromArtifact = run(loadShipped());
        List<Record> fromCode = run(CadMappingFixture.mapping());

        assertThat(fromArtifact)
                .as("if these diverge, the artifact is not the mapping the engine tests exercise")
                .containsExactlyElementsOf(fromCode);
    }

    private static List<Record> run(MappingDefinition definition) {
        MappingPipeline pipeline = new MappingPipeline(
                definition,
                CadMappingFixture.contracts(),
                Map.of("bundled-deterministic",
                        new DeterministicResolutionProvider(new InMemoryClusterIndex())),
                CadMappingFixture.canonicalTypes(),
                new QuarantineSink.InMemory(),
                ObservabilityEmitter.discarding());

        return CadMappingFixture.envelopes().stream()
                .flatMap(envelope -> pipeline.process(envelope, "run-1").canonicalRecords().stream())
                .toList();
    }

    @Test
    @DisplayName("a mapping loaded from disk canonicalises real source messiness")
    void loadedMappingHandlesMessySource() {
        List<Record> records = run(loadShipped());

        Person person = records.stream()
                .filter(record -> record.typeName().endsWith("#Person"))
                .map(Person::fromRecord)
                .findFirst()
                .orElseThrow();
        Incident incident = records.stream()
                .filter(record -> record.typeName().endsWith("#Incident"))
                .map(Incident::fromRecord)
                .findFirst()
                .orElseThrow();

        assertThat(person.surName()).isEqualTo("DOE");
        assertThat(person.givenName()).isEqualTo("JANE");
        assertThat(person.middleName()).isEqualTo("M");
        assertThat(person.driverLicenseId()).isEqualTo("K4471902");
        assertThat(incident.reportedDateTime()).isEqualTo(Instant.parse("2026-03-04T18:20:00Z"));
    }
}
