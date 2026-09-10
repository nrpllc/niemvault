package gov.niemplatform.modules.le;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.canonical.core.CoreCanonicalTypes;
import gov.niemplatform.canonical.core.Incident;
import gov.niemplatform.canonical.core.Person;
import gov.niemplatform.canonical.core.PersonIncidentAssociation;
import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.canonical.meta.CanonicalTypeResolver;
import gov.niemplatform.canonical.meta.TenantId;
import gov.niemplatform.connectors.api.ConnectorConfig;
import gov.niemplatform.connectors.api.LandingService;
import gov.niemplatform.connectors.file.FileDropConnector;
import gov.niemplatform.contracts.ContractLoader;
import gov.niemplatform.contracts.HopContract;
import gov.niemplatform.contracts.QuarantineSink;
import gov.niemplatform.identity.api.InMemoryClusterIndex;
import gov.niemplatform.identity.api.IndexedResolutionProvider;
import gov.niemplatform.identity.internal.DeterministicResolutionProvider;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The connection the series fixture exists to expose, asserted so it cannot quietly disappear.
 *
 * <p>{@code incidents-series.csv} is built so that one person is a bystander at three burglaries and
 * a suspect in a fourth incident — a fact no single record states. It only holds together because
 * identity resolution unifies four spellings of her licence and two of her name. Tidy those
 * spellings up, and the fixture still loads, still maps, still passes every contract, and quietly
 * describes four different people instead of one.
 *
 * <p>That is precisely the silent corruption spec §4.2 exists to prevent, applied to a fixture
 * rather than to a feed, so it gets a test rather than a comment.
 */
class CadSeriesTest {

    private static final String SOURCE_ID = "riverton-pd-cad";
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-03-12T18:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path work;

    private final InMemoryClusterIndex clusterIndex =
            new InMemoryClusterIndex(TenantId.of("test.agency"));

    private static InputStream resource(String path) {
        InputStream stream = CadSeriesTest.class.getResourceAsStream(path);
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
        ContractLoader loader = new ContractLoader(CanonicalTypeResolver.of(CoreCanonicalTypes.ALL));
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

    /** Lands the series fixture and maps everything that landed. */
    private List<Record> series() throws IOException {
        Path dropDir = Files.createDirectories(work.resolve("drop"));
        Path bronzeDir = Files.createDirectories(work.resolve("bronze"));
        try (InputStream source = resource("/fixtures/incidents-series.csv")) {
            Files.copy(source, dropDir.resolve("incidents-series.csv"));
        }

        ConnectorConfig config = ConnectorConfig.of(SOURCE_ID, "file-drop-01", FileDropConnector.TYPE,
                Map.of("directory", dropDir.toString(), "filePattern", "*.csv", "skipHeaderLines", "1"));
        FileDropConnector connector = new FileDropConnector(FIXED);
        connector.configure(config);

        QuarantineSink.InMemory quarantine = new QuarantineSink.InMemory();
        RecordingObservabilityEmitter emitter = new RecordingObservabilityEmitter();
        List<Record> produced = new ArrayList<>();

        try (ParquetBronzeStore bronze =
                new ParquetBronzeStore(bronzeDir, TenantId.of("test.agency"), FIXED)) {

            var landing = new LandingService(bronze, emitter, FIXED, 1_000)
                    .land(connector, config, "series");

            MappingPipeline pipeline = new MappingPipeline(
                    mapping(),
                    contracts(),
                    Map.of(DeterministicResolutionProvider.PROVIDER_ID, new IndexedResolutionProvider(
                            new DeterministicResolutionProvider(clusterIndex), clusterIndex)),
                    canonicalTypes(),
                    quarantine,
                    emitter);

            BronzeRange landed = BronzeRange.between(
                    landing.receipts().getFirst().batchId(), landing.receipts().getLast().batchId());
            try (Stream<RawEnvelope> envelopes = bronze.read(SOURCE_ID, landed)) {
                envelopes.forEach(envelope ->
                        produced.addAll(pipeline.process(envelope, "series").canonicalRecords()));
            }
        }

        // A fixture that quarantines is not demonstrating a connection, it is demonstrating drift.
        assertThat(quarantine.held()).isEmpty();
        return produced;
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

    /**
     * The canonical identity of the one person with this surname.
     *
     * <p>Asserting there is exactly one is half the point of the fixture: if a spelling variant
     * splits her into two clusters, the connection this file exists to show is a coincidence of
     * spelling rather than a fact about a human, and the test says so here rather than failing
     * later with a confusing count.
     */
    private static String personIdBySurname(List<Record> records, String surname) {
        Set<String> ids = new LinkedHashSet<>();
        people(records).stream()
                .filter(person -> surname.equals(person.surName()))
                .forEach(person -> ids.add(person.canonicalId().value()));

        assertThat(ids)
                .as("%s must resolve to exactly one canonical person", surname)
                .hasSize(1);
        return ids.iterator().next();
    }

    /** What role a person held on each incident they appear on, by agency incident number. */
    private static Map<String, String> rolesFor(List<Record> records, String canonicalPersonId) {
        Map<String, String> numberByCanonicalId = new LinkedHashMap<>();
        incidents(records).forEach(incident ->
                numberByCanonicalId.put(incident.canonicalId().value(), incident.incidentNumber()));

        Map<String, String> roles = new LinkedHashMap<>();
        for (PersonIncidentAssociation association : associations(records)) {
            if (canonicalPersonId.equals(association.person().id().value())) {
                roles.put(
                        numberByCanonicalId.get(association.incident().id().value()),
                        association.involvementCode());
            }
        }
        return roles;
    }

    @Test
    @DisplayName("twelve rows collapse to five incidents and six people")
    void producesTheDesignedShape() throws IOException {
        List<Record> records = series();

        // One Incident record per row, because the export is denormalised -- so twelve records
        // carrying five incident numbers.
        assertThat(incidents(records))
                .hasSize(12)
                .extracting(Incident::incidentNumber)
                .containsOnly(
                        "2026-000210", "2026-000237", "2026-000262", "2026-000288", "2026-000301");

        // And five identities, because an incident number is one incident however many rows repeat
        // it. Derived identity is what collapses them; without it the graph would hold twelve
        // incidents and every connection through them would be lost.
        assertThat(incidents(records).stream()
                .map(incident -> incident.canonicalId().value())
                .distinct())
                .hasSize(5);

        assertThat(clusterIndex.clusterCount("Person")).isEqualTo(6);
        assertThat(associations(records)).hasSize(12);
    }

    @Test
    @DisplayName("the same human is a bystander at three burglaries and a suspect in a fourth")
    void exposesTheConnectionAcrossIncidents() throws IOException {
        List<Record> records = series();

        // Four appearances, four rows, one person -- and only because resolution saw through
        // R88-40021, R8840021 and r8840021, and through a middle initial present three times and
        // absent once.
        String chen = personIdBySurname(records, "CHEN");
        Map<String, String> roles = rolesFor(records, chen);

        assertThat(roles.keySet()).containsExactlyInAnyOrder(
                "2026-000210", "2026-000237", "2026-000262", "2026-000288");

        // The reveal. On three she is a witness or the reporting party; on the fourth she is the
        // suspect. No single record says that, and no dispatcher would notice it.
        assertThat(roles).containsEntry("2026-000288", "SUSPECT");
        assertThat(roles.values()).filteredOn("SUSPECT"::equals).hasSize(1);
    }

    @Test
    @DisplayName("a person with no licence at all still resolves, on name and date of birth")
    void resolvesOnTheWeakerTier() throws IOException {
        List<Record> records = series();

        // MERCER carries UNK on one row and N/A on the other, so tier 1 has nothing to work with.
        // He is the corroborating thread -- the same suspect on two of the three burglaries -- and
        // he is in the fixture to put the resolver's weaker tier on a question that matters.
        Map<String, String> roles = rolesFor(records, personIdBySurname(records, "MERCER"));

        assertThat(roles.keySet()).containsExactlyInAnyOrder("2026-000237", "2026-000262");
        assertThat(roles.values()).containsOnly("SUSPECT");
    }

    @Test
    @DisplayName("not every edge is a lead, which is what real data looks like")
    void carriesUnremarkableConnectionsToo() throws IOException {
        List<Record> records = series();

        // A demonstration where every edge means something teaches the wrong lesson about what
        // these projections look like on a real feed.
        assertThat(rolesFor(records, personIdBySurname(records, "WHITFIELD")).keySet())
                .containsExactlyInAnyOrder("2026-000262", "2026-000301");
        assertThat(rolesFor(records, personIdBySurname(records, "ALVAREZ")).keySet())
                .containsExactly("2026-000210");
    }
}
