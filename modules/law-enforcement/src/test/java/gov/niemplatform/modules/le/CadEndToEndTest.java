package gov.niemplatform.modules.le;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.canonical.core.CoreCanonicalTypes;
import gov.niemplatform.canonical.core.Incident;
import gov.niemplatform.canonical.core.Person;
import gov.niemplatform.canonical.core.PersonIncidentAssociation;
import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.canonical.meta.CanonicalTypeResolver;
import gov.niemplatform.connectors.api.ConnectorConfig;
import gov.niemplatform.connectors.api.LandingService;
import gov.niemplatform.connectors.file.FileDropConnector;
import gov.niemplatform.contracts.ContractLoader;
import gov.niemplatform.contracts.HopContract;
import gov.niemplatform.contracts.QuarantineSink;
import gov.niemplatform.identity.api.InMemoryClusterIndex;
import gov.niemplatform.identity.api.IndexedResolutionProvider;
import gov.niemplatform.identity.internal.DeterministicResolutionProvider;
import gov.niemplatform.observability.ContractViolation;
import gov.niemplatform.observability.EventType;
import gov.niemplatform.observability.RecordingObservabilityEmitter;
import gov.niemplatform.runtime.engine.MappingDefinition;
import gov.niemplatform.runtime.engine.MappingLoader;
import gov.niemplatform.runtime.engine.MappingPipeline;
import gov.niemplatform.storage.api.BronzeRange;
import gov.niemplatform.storage.api.RawEnvelope;
import gov.niemplatform.storage.parquet.ParquetBronzeStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Phase 1 vertical slice, end to end, using the artifacts this module actually ships.
 *
 * <p>Every other test in the build exercises one component. This one runs a CAD export through the
 * whole spine -- file drop connector, bronze landing, the mapping and contracts loaded from their
 * YAML artifacts, identity resolution, canonical output -- and asserts acceptance criteria 1, 2,
 * 3, and 5 against it.
 *
 * <p>Nothing here is hand-built. If a contract or the mapping on disk is wrong, this test is where
 * it shows up, which is the point of putting it in the module that owns them.
 */
class CadEndToEndTest {

    private static final String SOURCE_ID = "riverton-pd-cad";
    private static final Instant NOW = Instant.parse("2026-03-09T18:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path drop;

    @TempDir
    Path bronzeRoot;

    private RecordingObservabilityEmitter emitter;
    private QuarantineSink.InMemory quarantine;
    private InMemoryClusterIndex clusterIndex;
    private int runCounter;

    @BeforeEach
    void setUp() {
        emitter = new RecordingObservabilityEmitter();
        quarantine = new QuarantineSink.InMemory();
        clusterIndex = new InMemoryClusterIndex(gov.niemplatform.canonical.meta.TenantId.of("test.agency"));
    }

    // --- artifacts -------------------------------------------------------

    private static InputStream resource(String path) {
        InputStream stream = CadEndToEndTest.class.getResourceAsStream(path);
        if (stream == null) {
            throw new AssertionError("shipped artifact is missing from the module: " + path);
        }
        return stream;
    }

    private static MappingDefinition mapping() {
        try (InputStream artifact = resource("/mappings/cad-to-canonical-1.0.0.yaml")) {
            return new MappingLoader().load(artifact, "cad-to-canonical-1.0.0.yaml");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, HopContract> contracts() {
        CanonicalTypeResolver resolver = CanonicalTypeResolver.of(CoreCanonicalTypes.ALL);
        ContractLoader loader = new ContractLoader(resolver);
        Map<String, HopContract> byHop = new LinkedHashMap<>();

        for (String name : List.of(
                "cad-incident-to-canonical-1.0.0.yaml",
                "cad-person-to-canonical-1.0.0.yaml",
                "cad-association-to-canonical-1.0.0.yaml")) {
            try (InputStream artifact = resource("/contracts/" + name)) {
                HopContract contract = loader.load(artifact, name);
                byHop.put(contract.hopId(), contract);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return byHop;
    }

    private static Map<String, CanonicalTypeDescriptor> canonicalTypes() {
        Map<String, CanonicalTypeDescriptor> types = new LinkedHashMap<>();
        CoreCanonicalTypes.ALL.forEach(type -> types.put(type.name(), type));
        return types;
    }

    // --- the run ---------------------------------------------------------

    /**
     * Copies a fixture into a fresh drop directory, lands it, and maps everything that landed.
     *
     * <p>Each call gets its own drop and bronze directories. Bronze is append-only, so reusing a
     * root across calls would land the same export twice and map twenty envelopes where the test
     * meant ten.
     */
    private List<Record> run(String fixture) throws IOException {
        String workspace = "run-" + runCounter++;
        Path dropDir = Files.createDirectories(drop.resolve(workspace));
        Path bronzeDir = Files.createDirectories(bronzeRoot.resolve(workspace));

        try (InputStream source = resource("/fixtures/" + fixture)) {
            Files.copy(source, dropDir.resolve(fixture));
        }

        ConnectorConfig config = ConnectorConfig.of(SOURCE_ID, "file-drop-01", FileDropConnector.TYPE,
                Map.of("directory", dropDir.toString(), "filePattern", "*.csv", "skipHeaderLines", "1"));

        FileDropConnector connector = new FileDropConnector(FIXED);
        connector.configure(config);

        try (ParquetBronzeStore bronze = new ParquetBronzeStore(bronzeDir, gov.niemplatform.canonical.meta.TenantId.of("test.agency"), FIXED)) {
            new LandingService(bronze, emitter, FIXED, 1_000).land(connector, config, "run-1");

            MappingPipeline pipeline = new MappingPipeline(
                    mapping(),
                    contracts(),
                    Map.of("bundled-deterministic", new IndexedResolutionProvider(
                            new DeterministicResolutionProvider(clusterIndex), clusterIndex)),
                    canonicalTypes(),
                    quarantine,
                    emitter);

            try (Stream<RawEnvelope> landed = bronze.read(SOURCE_ID, BronzeRange.all())) {
                return landed
                        .flatMap(envelope -> pipeline.process(envelope, "run-1").canonicalRecords().stream())
                        .toList();
            }
        }
    }

    private static List<Person> people(List<Record> records) {
        return records.stream()
                .filter(record -> record.typeName().endsWith("#Person"))
                .map(Person::fromRecord)
                .toList();
    }

    private static List<Incident> incidents(List<Record> records) {
        return records.stream()
                .filter(record -> record.typeName().endsWith("#Incident"))
                .map(Incident::fromRecord)
                .toList();
    }

    private static List<PersonIncidentAssociation> associations(List<Record> records) {
        return records.stream()
                .filter(record -> record.typeName().endsWith("#PersonIncidentAssociation"))
                .map(PersonIncidentAssociation::fromRecord)
                .toList();
    }

    // --- criteria --------------------------------------------------------

    @Test
    @DisplayName("criterion 1: the export lands in bronze byte-preserved")
    void landsBytePreserved() throws IOException {
        Path dropDir = Files.createDirectories(drop.resolve("landing"));
        try (InputStream source = resource("/fixtures/incidents.csv")) {
            Files.copy(source, dropDir.resolve("incidents.csv"));
        }
        ConnectorConfig config = ConnectorConfig.of(SOURCE_ID, "file-drop-01", FileDropConnector.TYPE,
                Map.of("directory", dropDir.toString(), "filePattern", "*.csv", "skipHeaderLines", "1"));
        FileDropConnector connector = new FileDropConnector(FIXED);
        connector.configure(config);

        try (ParquetBronzeStore bronze = new ParquetBronzeStore(bronzeRoot, gov.niemplatform.canonical.meta.TenantId.of("test.agency"), FIXED)) {
            new LandingService(bronze, emitter, FIXED, 1_000).land(connector, config, "run-1");

            try (Stream<RawEnvelope> landed = bronze.read(SOURCE_ID, BronzeRange.all())) {
                List<RawEnvelope> envelopes = landed.toList();
                assertThat(envelopes).hasSize(10);
                assertThat(envelopes.getFirst().payloadAsText())
                        .isEqualTo("2026-000114,BURG,2026/03/04 11:20,\"418 W 9TH ST\",3A,VICT,"
                                + "\"DOE, JANE M\",03/14/1988,F,K447-1902");
                assertThat(envelopes.getFirst().contentHash().algorithm()).isEqualTo("sha256");
            }
        }
    }

    @Test
    @DisplayName("criterion 2: every row maps to Person, Incident, and their association")
    void mapsEveryRow() throws IOException {
        List<Record> canonical = run("incidents.csv");

        assertThat(canonical).hasSize(30);
        assertThat(people(canonical)).hasSize(10);
        assertThat(incidents(canonical)).hasSize(10);
        assertThat(associations(canonical)).hasSize(10);
        assertThat(emitter.countOfType(EventType.CONTRACT_VIOLATION))
                .as("a clean export produces no violations")
                .isZero();
    }

    @Test
    @DisplayName("the messiness in a real export is normalised away")
    void normalisesSourceMessiness() throws IOException {
        List<Person> resolved = people(run("incidents.csv"));

        // "DOE, JANE M" -> surname, given name, middle initial.
        assertThat(resolved.getFirst().surName()).isEqualTo("DOE");
        assertThat(resolved.getFirst().givenName()).isEqualTo("JANE");
        assertThat(resolved.getFirst().middleName()).isEqualTo("M");

        // UNK, N/A and NONE are agency sentinels, not licence numbers.
        assertThat(resolved).filteredOn(person -> "NAKAMURA".equals(person.surName()))
                .allSatisfy(person -> assertThat(person.driverLicenseId()).isNull());
        assertThat(resolved).filteredOn(person -> "CHEN".equals(person.surName()))
                .allSatisfy(person -> assertThat(person.driverLicenseId()).isNull());

        // K447-1902, K4471902 and "K447 1902" are one licence written three ways.
        assertThat(resolved).filteredOn(person -> "DOE".equals(person.surName()))
                .allSatisfy(person -> assertThat(person.driverLicenseId()).isEqualTo("K4471902"));
    }

    @Test
    @DisplayName("criterion 3: repeat appearances of one human resolve to a single cluster")
    void samePersonResolvesToOneCluster() throws IOException {
        List<Person> resolved = people(run("incidents.csv"));

        Map<String, Long> byCluster = new LinkedHashMap<>();
        resolved.forEach(person ->
                byCluster.merge(person.canonicalId().value(), 1L, Long::sum));

        // Ten person rows, six humans: Doe appears three times, O'Brien and Chen twice each.
        assertThat(byCluster).hasSize(6);
        assertThat(clusterIndex.clusterCount("Person")).isEqualTo(6);

        assertThat(clusterFor(resolved, "DOE")).hasSize(1);
        assertThat(clusterFor(resolved, "OBRIEN"))
                .as("O'BRIEN and OBRIEN share a licence written two ways")
                .hasSize(1);
        assertThat(clusterFor(resolved, "CHEN"))
                .as("no licence, but the same normalised name and date of birth")
                .hasSize(1);
    }

    private static List<String> clusterFor(List<Person> people, String surname) {
        return people.stream()
                .filter(person -> surname.equals(person.surName()))
                .map(person -> person.canonicalId().value())
                .distinct()
                .toList();
    }

    @Test
    @DisplayName("incidents are identified by their source key, not by resolution")
    void incidentsKeyedBySourceNumber() throws IOException {
        List<Incident> mapped = incidents(run("incidents.csv"));

        assertThat(mapped).extracting(incident -> incident.canonicalId().value())
                .contains("INC/RIVERTON-PD/2026-000114");
        assertThat(mapped.stream().map(incident -> incident.canonicalId().value()).distinct().toList())
                .as("two rows for one incident are one incident")
                .hasSize(8);
    }

    @Test
    @DisplayName("associations link the resolved person to the identified incident")
    void associationsLinkBothSides() throws IOException {
        List<Record> canonical = run("incidents.csv");
        PersonIncidentAssociation first = associations(canonical).getFirst();

        assertThat(first.involvementCode()).isEqualTo("VICTIM");
        assertThat(first.person().typeName()).isEqualTo("Person");
        assertThat(first.incident().typeName()).isEqualTo("Incident");
        assertThat(first.person().id()).isEqualTo(people(canonical).getFirst().canonicalId());
        assertThat(first.incident().id().value()).isEqualTo("INC/RIVERTON-PD/2026-000114");
    }

    @Nested
    @DisplayName("criterion 5: a drifting source is caught, quarantined, and reported")
    class Drift {

        @Test
        @DisplayName("a changed date format is caught, and only the affected hop suffers")
        void driftedDateOfBirth() throws IOException {
            List<Record> canonical = run("incidents-drifted.csv");

            assertThat(emitter.countOfType(EventType.CONTRACT_VIOLATION)).isPositive();
            assertThat(quarantine.size()).isPositive();
            assertThat(quarantine.heldAt("map-person")).isNotEmpty();

            // The row whose date of birth drifted still yields its incident: the incident hop
            // does not consume DOB, and its contract does not constrain it.
            assertThat(incidents(canonical))
                    .extracting(incident -> incident.incidentNumber())
                    .contains("2026-000301");
            assertThat(people(canonical))
                    .extracting(Person::surName)
                    .doesNotContain("OKAFOR");
        }

        @Test
        @DisplayName("a changed incident timestamp format fails the incident hop, not the person hop")
        void driftedIncidentTimestamp() throws IOException {
            List<Record> canonical = run("incidents-drifted.csv");

            assertThat(incidents(canonical))
                    .extracting(incident -> incident.incidentNumber())
                    .doesNotContain("2026-000302");
            assertThat(people(canonical))
                    .extracting(Person::surName)
                    .as("the person hop does not read RPT_DTTM")
                    .contains("PARK");
        }

        @Test
        @DisplayName("an added column is caught by every hop that sees it")
        void addedColumn() throws IOException {
            run("incidents-drifted.csv");

            assertThat(emitter.eventsOfType(ContractViolation.class))
                    .anySatisfy(violation -> assertThat(violation.failures())
                            .anySatisfy(failure ->
                                    assertThat(failure.rule()).isEqualTo("unexpectedField")));
        }

        @Test
        @DisplayName("the pipeline does not halt: clean rows in the same file still map")
        void cleanRowsStillMap() throws IOException {
            List<Record> canonical = run("incidents-drifted.csv");

            assertThat(people(canonical)).extracting(Person::surName)
                    .contains("HALE", "LOPEZ");
        }

        @Test
        @DisplayName("no source value reaches a violation event")
        void violationsRedactValues() throws IOException {
            run("incidents-drifted.csv");

            String rendered = emitter.eventsOfType(ContractViolation.class).stream()
                    .map(violation -> violation.attributes().toString())
                    .reduce("", String::concat);

            assertThat(rendered)
                    .doesNotContain("OKAFOR", "1979-12-01", "R5540021")
                    .contains("####-##-##");
        }
    }

    @Test
    @DisplayName("the same export mapped twice produces identical canonical output")
    void mappingIsDeterministic() throws IOException {
        List<Record> first = run("incidents.csv");

        // A completely fresh platform: new emitter, new quarantine, new cluster index, and its
        // own bronze. If anything carried over, this would not be a determinism test.
        setUp();
        List<Record> second = run("incidents.csv");

        assertThat(second)
                .as("acceptance criterion 6 depends on this holding before replay is even involved")
                .containsExactlyElementsOf(first);
    }
}
