package gov.niemplatform.runtime.engine;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.canonical.core.Incident;
import gov.niemplatform.canonical.core.Person;
import gov.niemplatform.canonical.core.PersonIncidentAssociation;
import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.contracts.QuarantineSink;
import gov.niemplatform.identity.api.ResolutionProvider;
import gov.niemplatform.observability.ContractViolation;
import gov.niemplatform.observability.EventType;
import gov.niemplatform.observability.RecordingObservabilityEmitter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The mapping DAG, exercised without Flink.
 *
 * <p>Covers acceptance criteria 2, 3, and 5. Criterion 7 needs the Flink layer and lives in
 * {@link FlinkMappingJobTest}; keeping these separate is deliberate, because a failure here is a
 * mapping problem and a failure there is an execution problem, and conflating them would make both
 * harder to diagnose.
 */
class MappingPipelineTest {

    private RecordingObservabilityEmitter emitter;
    private QuarantineSink.InMemory quarantine;
    private ResolutionProvider resolver;

    @BeforeEach
    void setUp() {
        emitter = new RecordingObservabilityEmitter();
        quarantine = new QuarantineSink.InMemory();
        resolver = new CadMappingFixture.TestDoubleResolver();
    }

    private MappingPipeline pipeline() {
        return new MappingPipeline(
                CadMappingFixture.mapping(),
                CadMappingFixture.contracts(),
                Map.of("bundled-deterministic", resolver),
                CadMappingFixture.canonicalTypes(),
                quarantine,
                emitter);
    }

    private static Record ofType(List<Record> records, String canonicalName) {
        return records.stream()
                .filter(record -> record.typeName().endsWith("#" + canonicalName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no " + canonicalName + " among " + records.stream().map(Record::typeName).toList()));
    }

    @Test
    @DisplayName("criterion 2: one source row maps to Person, Incident, and their association")
    void mapsToAllThreeCanonicalTypes() {
        MappingPipeline.Outcome outcome = pipeline()
                .process(CadMappingFixture.envelope(CadMappingFixture.ROW_BURGLARY_VICTIM, 2), "run-1");

        assertThat(outcome.fullyMapped()).isTrue();
        assertThat(outcome.canonicalRecords()).hasSize(3);
        assertThat(emitter.emitted()).isEmpty();
        assertThat(quarantine.size()).isZero();
    }

    @Test
    @DisplayName("the mapped Incident carries normalised, typed values")
    void incidentIsCanonicalised() {
        List<Record> records = pipeline()
                .process(CadMappingFixture.envelope(CadMappingFixture.ROW_BURGLARY_VICTIM, 2), "run-1")
                .canonicalRecords();

        Incident incident = Incident.fromRecord(ofType(records, "Incident"));

        assertThat(incident.incidentNumber()).isEqualTo("2026-000114");
        assertThat(incident.callTypeCode()).isEqualTo("BURG");
        assertThat(incident.beat()).isEqualTo("3A");
        assertThat(incident.locationAddressText()).isEqualTo("418 W 9TH ST");
        // 11:20 Mountain Daylight Time, per the mapping's declared zone.
        assertThat(incident.reportedDateTime()).isEqualTo(Instant.parse("2026-03-04T18:20:00Z"));
        assertThat(incident.canonicalId().value()).isEqualTo("INC/RIVERTON-PD/2026-000114");
    }

    @Test
    @DisplayName("the packed name field is split, and the agency sentinel becomes absence")
    void personIsCanonicalised() {
        List<Record> records = pipeline()
                .process(CadMappingFixture.envelope(CadMappingFixture.ROW_OTHER_PERSON, 3), "run-1")
                .canonicalRecords();

        Person person = Person.fromRecord(ofType(records, "Person"));

        assertThat(person.surName()).isEqualTo("RIVERA");
        assertThat(person.givenName()).isEqualTo("LUIS");
        assertThat(person.birthDate()).isEqualTo(LocalDate.of(1975, 11, 2));
        assertThat(person.sexCode()).isEqualTo("M");
        assertThat(person.driverLicenseId())
                .as("UNK is the agency's sentinel for 'not recorded', not a licence number")
                .isNull();
    }

    @Test
    @DisplayName("the association links the two entities by role, not by foreign key")
    void associationCarriesRoleReferences() {
        List<Record> records = pipeline()
                .process(CadMappingFixture.envelope(CadMappingFixture.ROW_BURGLARY_VICTIM, 2), "run-1")
                .canonicalRecords();

        PersonIncidentAssociation association =
                PersonIncidentAssociation.fromRecord(ofType(records, "PersonIncidentAssociation"));
        Person person = Person.fromRecord(ofType(records, "Person"));

        assertThat(association.involvementCode()).isEqualTo("VICTIM");
        assertThat(association.person().typeName()).isEqualTo("Person");
        assertThat(association.person().id()).isEqualTo(person.canonicalId());
        assertThat(association.incident().id().value()).isEqualTo("INC/RIVERTON-PD/2026-000114");
    }

    @Test
    @DisplayName("criterion 3: two source records for the same human resolve to one cluster")
    void samePersonResolvesToOneCluster() {
        MappingPipeline pipeline = pipeline();

        Person first = Person.fromRecord(ofType(pipeline
                .process(CadMappingFixture.envelope(CadMappingFixture.ROW_BURGLARY_VICTIM, 2), "run-1")
                .canonicalRecords(), "Person"));
        Person other = Person.fromRecord(ofType(pipeline
                .process(CadMappingFixture.envelope(CadMappingFixture.ROW_OTHER_PERSON, 3), "run-1")
                .canonicalRecords(), "Person"));
        Person again = Person.fromRecord(ofType(pipeline
                .process(CadMappingFixture.envelope(CadMappingFixture.ROW_SAME_PERSON_LATER, 4), "run-1")
                .canonicalRecords(), "Person"));

        assertThat(again.canonicalId())
                .as("K447-1902 and K4471902 are the same licence")
                .isEqualTo(first.canonicalId());
        assertThat(other.canonicalId())
                .as("a different human must not be merged")
                .isNotEqualTo(first.canonicalId());
    }

    @Nested
    @DisplayName("the pipeline keeps its own books")
    class Completeness {

        @Test
        @DisplayName("a clean run balances: every hop of every record accounted for")
        void cleanRunBalances() {
            MappingPipeline pipeline = pipeline();

            pipeline.process(CadMappingFixture.envelope(CadMappingFixture.ROW_BURGLARY_VICTIM, 2), "run-1");
            pipeline.process(CadMappingFixture.envelope(CadMappingFixture.ROW_OTHER_PERSON, 3), "run-1");

            assertThat(pipeline.account().landedCount()).isEqualTo(2);
            assertThat(pipeline.account().producedCount()).isEqualTo(6);
            assertThat(pipeline.account().balances()).isTrue();
            assertThat(pipeline.reportCompleteness("run-1")).isTrue();
        }

        @Test
        @DisplayName("a quarantining run still balances, because a rejection is not a loss")
        void quarantiningRunBalances() {
            // The distinction the whole account exists to preserve. §4.2 requires bad data not to
            // halt the pipeline, so quarantining is routine -- and that is exactly what makes a
            // genuine drop invisible unless something has to add up.
            MappingPipeline pipeline = pipeline();
            String corrupted = CadMappingFixture.ROW_BURGLARY_VICTIM.replace("03/14/1988", "1988-03-14");

            pipeline.process(CadMappingFixture.envelope(corrupted, 2), "run-1");

            assertThat(pipeline.account().quarantinedCount()).isPositive();
            assertThat(pipeline.account().balances())
                    .as("a record the platform can show you is accounted for")
                    .isTrue();
        }

        @Test
        @DisplayName("a skipped dependent hop is accounted for, not missing")
        void skippedHopIsAccountedFor() {
            MappingPipeline pipeline = pipeline();
            String corrupted = CadMappingFixture.ROW_BURGLARY_VICTIM.replace("03/14/1988", "1988-03-14");

            MappingPipeline.Outcome outcome =
                    pipeline.process(CadMappingFixture.envelope(corrupted, 2), "run-1");

            assertThat(outcome.skippedHops()).isNotEmpty();
            assertThat(pipeline.account().skippedCount()).isEqualTo(outcome.skippedHops().size());
            assertThat(pipeline.account().balances()).isTrue();
        }

        @Test
        @DisplayName("emits nothing when the run balances")
        void balancedRunEmitsNothing() {
            // An ERROR saying nothing is wrong is how an event stream stops being read.
            MappingPipeline pipeline = pipeline();
            pipeline.process(CadMappingFixture.envelope(CadMappingFixture.ROW_BURGLARY_VICTIM, 2), "run-1");

            int before = emitter.emitted().size();
            assertThat(pipeline.reportCompleteness("run-1")).isTrue();
            assertThat(emitter.emitted()).hasSize(before);
        }
    }

    @Nested
    @DisplayName("criterion 5: bad data is quarantined and reported, and the pipeline continues")
    class Corruption {

        @Test
        @DisplayName("a drifted date shape produces a violation and a quarantined record")
        void driftedDateIsCaught() {
            // The source starts writing ISO dates where the contract declares MM/dd/yyyy.
            String corrupted = CadMappingFixture.ROW_BURGLARY_VICTIM.replace("03/14/1988", "1988-03-14");

            MappingPipeline.Outcome outcome =
                    pipeline().process(CadMappingFixture.envelope(corrupted, 2), "run-1");

            assertThat(emitter.countOfType(EventType.CONTRACT_VIOLATION)).isPositive();
            assertThat(quarantine.size()).isPositive();
            assertThat(outcome.quarantinedHops()).contains("map-person");
        }

        @Test
        @DisplayName("the pipeline does not halt: the unaffected hop still produces its record")
        void unaffectedHopsStillProduce() {
            String corrupted = CadMappingFixture.ROW_BURGLARY_VICTIM.replace("03/14/1988", "1988-03-14");

            MappingPipeline.Outcome outcome =
                    pipeline().process(CadMappingFixture.envelope(corrupted, 2), "run-1");

            assertThat(outcome.canonicalRecords())
                    .as("the incident does not depend on the person hop")
                    .anySatisfy(record -> assertThat(record.typeName()).endsWith("#Incident"));
        }

        @Test
        @DisplayName("a hop depending on a failed hop is skipped, not left dangling")
        void dependentHopIsSkipped() {
            String corrupted = CadMappingFixture.ROW_BURGLARY_VICTIM.replace("03/14/1988", "1988-03-14");

            MappingPipeline.Outcome outcome =
                    pipeline().process(CadMappingFixture.envelope(corrupted, 2), "run-1");

            assertThat(outcome.skippedHops()).contains("map-person-incident");
            assertThat(outcome.canonicalRecords())
                    .noneSatisfy(record ->
                            assertThat(record.typeName()).endsWith("#PersonIncidentAssociation"));
        }

        @Test
        @DisplayName("bad data does not stop the next record from mapping")
        void goodRecordsKeepFlowing() {
            MappingPipeline pipeline = pipeline();
            String corrupted = CadMappingFixture.ROW_BURGLARY_VICTIM.replace("03/14/1988", "1988-03-14");

            pipeline.process(CadMappingFixture.envelope(corrupted, 2), "run-1");
            MappingPipeline.Outcome afterwards =
                    pipeline.process(CadMappingFixture.envelope(CadMappingFixture.ROW_OTHER_PERSON, 3), "run-1");

            assertThat(afterwards.fullyMapped()).isTrue();
            assertThat(afterwards.canonicalRecords()).hasSize(3);
        }

        @Test
        @DisplayName("a source adding a column is caught by the input gate")
        void unexpectedColumnIsCaught() {
            String widened = CadMappingFixture.ROW_BURGLARY_VICTIM + ",GANG_UNK";

            pipeline().process(CadMappingFixture.envelope(widened, 2), "run-1");

            assertThat(emitter.eventsOfType(ContractViolation.class))
                    .anySatisfy(violation -> assertThat(violation.failures())
                            .anySatisfy(failure ->
                                    assertThat(failure.rule()).isEqualTo("unexpectedField")));
        }

        @Test
        @DisplayName("the violation names the offending value's shape, never the value")
        void violationRedactsValue() {
            String corrupted = CadMappingFixture.ROW_BURGLARY_VICTIM.replace("03/14/1988", "1988-03-14");

            pipeline().process(CadMappingFixture.envelope(corrupted, 2), "run-1");

            String rendered = emitter.eventsOfType(ContractViolation.class).stream()
                    .map(violation -> violation.attributes().toString())
                    .reduce("", String::concat);

            assertThat(rendered).doesNotContain("1988-03-14").contains("####-##-##");
        }
    }

    @Test
    @DisplayName("the same envelope mapped twice produces identical output")
    void mappingIsDeterministic() {
        List<Record> first = pipeline()
                .process(CadMappingFixture.envelope(CadMappingFixture.ROW_BURGLARY_VICTIM, 2), "run-1")
                .canonicalRecords();
        List<Record> second = pipeline()
                .process(CadMappingFixture.envelope(CadMappingFixture.ROW_BURGLARY_VICTIM, 2), "run-2")
                .canonicalRecords();

        assertThat(second).containsExactlyElementsOf(first);
    }
}
