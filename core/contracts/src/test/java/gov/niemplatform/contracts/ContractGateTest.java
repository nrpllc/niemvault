package gov.niemplatform.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.FieldType;
import gov.niemplatform.observability.ContractViolation;
import gov.niemplatform.observability.Direction;
import gov.niemplatform.observability.EventType;
import gov.niemplatform.observability.PipelineContext;
import gov.niemplatform.observability.RecordingObservabilityEmitter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What happens when a contract fails, which is the whole point of §4.2.
 *
 * <p>Acceptance criterion 5 in miniature: a deliberately corrupted source field must produce a
 * {@code ContractViolation} event and a quarantined record, and must neither halt the pipeline
 * nor let the bad record through. All four halves of that are asserted here.
 */
class ContractGateTest {

    private static final String SOURCE_TYPE = "source:cad-csv/person";
    private static final String CANONICAL_TYPE = "https://niemplatform.gov/canonical/core/1.0#Person";
    private static final PipelineContext RUN = PipelineContext.of("riverton-pd-cad", "run-1");

    private RecordingObservabilityEmitter emitter;
    private QuarantineSink.InMemory quarantine;
    private HopContract contract;

    @BeforeEach
    void setUp() {
        emitter = new RecordingObservabilityEmitter();
        quarantine = new QuarantineSink.InMemory();
        contract = new SchemaHopContract(
                ContractId.of("cad-person-to-canonical", "1.2.0"),
                "map-person",
                Schema.strict(SOURCE_TYPE, "1.0.0", List.of(
                        FieldExpectation.required("NAME_FULL", FieldType.STRING),
                        FieldExpectation.optional("DOB", FieldType.STRING)
                                .withPattern("^\\d{2}/\\d{2}/\\d{4}$"))),
                Schema.strict(CANONICAL_TYPE, "1.0.0", List.of(
                        FieldExpectation.required("canonicalId", FieldType.STRING),
                        FieldExpectation.required("surName", FieldType.STRING))));
    }

    private ContractGate gate(Direction direction) {
        return new ContractGate(contract, direction, quarantine, emitter);
    }

    private static Record goodSourceRecord() {
        return Record.builder(SOURCE_TYPE)
                .set("NAME_FULL", "DOE, JANE M")
                .set("DOB", "03/14/1988")
                .build();
    }

    @Test
    @DisplayName("a conforming record passes through silently")
    void conformingRecordPasses() {
        var passed = gate(Direction.INPUT).check(goodSourceRecord(), RUN);

        assertThat(passed).contains(goodSourceRecord());
        assertThat(emitter.emitted()).isEmpty();
        assertThat(quarantine.size()).isZero();
    }

    @Test
    @DisplayName("a corrupted field shape is quarantined and reported, and does not pass")
    void corruptedFieldIsQuarantinedAndReported() {
        Record corrupted = Record.builder(SOURCE_TYPE)
                .set("NAME_FULL", "DOE, JANE M")
                .set("DOB", "1988-03-14")
                .build();

        var passed = gate(Direction.INPUT).check(corrupted, RUN);

        assertThat(passed).as("the bad record must not reach the rest of the pipeline").isEmpty();
        assertThat(quarantine.held()).singleElement()
                .returns("map-person", QuarantinedRecord::hopId)
                .returns(Direction.INPUT, QuarantinedRecord::direction);
        assertThat(emitter.countOfType(EventType.CONTRACT_VIOLATION)).isEqualTo(1);
    }

    @Test
    @DisplayName("the violation names where the record went, so it is actionable")
    void violationCarriesQuarantineId() {
        gate(Direction.INPUT).check(
                Record.builder(SOURCE_TYPE).set("DOB", "03/14/1988").build(), RUN);

        ContractViolation violation = emitter.eventsOfType(ContractViolation.class).getFirst();

        assertThat(violation.quarantineId()).isNotBlank();
        assertThat(violation.attributes()).containsKey("quarantineId");
    }

    @Test
    @DisplayName("the violation carries the hop, the direction, and the contract version")
    void violationCarriesProvenance() {
        gate(Direction.OUTPUT).check(
                Record.builder(CANONICAL_TYPE).set("canonicalId", "c-1").build(), RUN);

        ContractViolation violation = emitter.eventsOfType(ContractViolation.class).getFirst();

        assertThat(violation.direction()).isEqualTo(Direction.OUTPUT);
        assertThat(violation.context().hopId()).isEqualTo("map-person");
        assertThat(violation.context().mappingVersion()).isEqualTo("1.2.0");
        assertThat(violation.context().sourceId()).isEqualTo("riverton-pd-cad");
        assertThat(violation.recordTypeName()).isEqualTo(CANONICAL_TYPE);
    }

    @Test
    @DisplayName("quarantine keeps the values that the event redacts")
    void quarantineKeepsValues() {
        Record corrupted = Record.builder(SOURCE_TYPE)
                .set("NAME_FULL", "DOE, JANE M")
                .set("DOB", "1988-03-14")
                .build();

        gate(Direction.INPUT).check(corrupted, RUN);

        QuarantinedRecord held = quarantine.held().getFirst();
        assertThat(held.record().get("DOB", String.class)).isEqualTo("1988-03-14");

        String event = emitter.eventsOfType(ContractViolation.class).getFirst().attributes().toString();
        assertThat(event).doesNotContain("1988-03-14");
    }

    @Test
    @DisplayName("a rejected record never halts the pipeline")
    void rejectionDoesNotHalt() {
        ContractGate inbound = gate(Direction.INPUT);

        assertThatCode(() -> {
            inbound.check(Record.builder(SOURCE_TYPE).build(), RUN);
            inbound.check(null, RUN);
            inbound.check(goodSourceRecord(), RUN);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("good records keep flowing after a bad one")
    void oneBadRecordDoesNotStopTheRest() {
        ContractGate inbound = gate(Direction.INPUT);

        inbound.check(Record.builder(SOURCE_TYPE).set("DOB", "bad").build(), RUN);
        var afterwards = inbound.check(goodSourceRecord(), RUN);

        assertThat(afterwards).isPresent();
        assertThat(quarantine.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("a null record is reported without a quarantine id, since there is nothing to hold")
    void nullRecordReportedNotHeld() {
        gate(Direction.OUTPUT).check(null, RUN);

        ContractViolation violation = emitter.eventsOfType(ContractViolation.class).getFirst();

        assertThat(violation.quarantineId()).isNull();
        assertThat(violation.recordTypeName()).isEqualTo(CANONICAL_TYPE);
        assertThat(quarantine.size()).isZero();
    }

    @Test
    @DisplayName("the two directions validate against different schemas")
    void directionsUseDifferentSchemas() {
        Record sourceShaped = goodSourceRecord();

        assertThat(gate(Direction.INPUT).check(sourceShaped, RUN)).isPresent();
        assertThat(gate(Direction.OUTPUT).check(sourceShaped, RUN))
                .as("a source-shaped record is not valid canonical output")
                .isEmpty();
    }
}
