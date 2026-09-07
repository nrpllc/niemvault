package gov.niemplatform.runtime.engine;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.contracts.QuarantineSink;
import gov.niemplatform.observability.ObservabilityEmitter;
import gov.niemplatform.storage.api.RawEnvelope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Acceptance criterion 7: the identical mapping definition runs unchanged in batch and streaming,
 * producing identical canonical output.
 *
 * <p>Spec §8 says this is the criterion that validates the core architectural bet, and that
 * Phase 1 is not done without it. It is the reason for the Flink decision in §2 and for keeping
 * {@link MappingPipeline} free of Flink entirely.
 *
 * <p>The test is only meaningful because there is nothing to keep in step: both runs build the
 * same job graph from the same {@link MappingDefinition} and invoke the same pipeline class. The
 * single difference is {@link ExecutionMode}. If someone later adds a mode-specific branch, this
 * test is what should stop them.
 */
class FlinkMappingJobTest {

    /**
     * Builds a pipeline inside the Flink operator.
     *
     * <p>Each run gets a fresh resolver, which is what makes the comparison fair: identity
     * resolution is stateful across records, so a shared resolver would let the batch run seed
     * clusters that the streaming run then merely found.
     */
    private static MappingPipelineFactory factory() {
        return () -> new MappingPipeline(
                CadMappingFixture.mapping(),
                CadMappingFixture.contracts(),
                Map.of("bundled-deterministic", new CadMappingFixture.TestDoubleResolver()),
                CadMappingFixture.canonicalTypes(),
                new QuarantineSink.InMemory(),
                ObservabilityEmitter.discarding());
    }

    @Test
    @DisplayName("criterion 7: batch and streaming produce identical canonical output")
    void batchAndStreamingAgree() throws Exception {
        List<RawEnvelope> envelopes = CadMappingFixture.envelopes();

        List<Record> batch = FlinkMappingJob.run(factory(), envelopes, ExecutionMode.BATCH, "run-batch");
        List<Record> streaming =
                FlinkMappingJob.run(factory(), envelopes, ExecutionMode.STREAMING, "run-streaming");

        assertThat(streaming)
                .as("the same mapping, the same input, two execution modalities")
                .containsExactlyElementsOf(batch);
    }

    @Test
    @DisplayName("both modalities map every record fully")
    void bothModalitiesProduceEverything() throws Exception {
        List<RawEnvelope> envelopes = CadMappingFixture.envelopes();

        List<Record> batch = FlinkMappingJob.run(factory(), envelopes, ExecutionMode.BATCH, "run-batch");

        // Three source rows, each yielding a Person, an Incident, and their association.
        assertThat(batch).hasSize(9);
        assertThat(batch).filteredOn(record -> record.typeName().endsWith("#Person")).hasSize(3);
        assertThat(batch).filteredOn(record -> record.typeName().endsWith("#Incident")).hasSize(3);
        assertThat(batch)
                .filteredOn(record -> record.typeName().endsWith("#PersonIncidentAssociation"))
                .hasSize(3);
    }

    @Test
    @DisplayName("identity resolution agrees across modalities, not just field values")
    void identityAgreesAcrossModalities() throws Exception {
        List<RawEnvelope> envelopes = CadMappingFixture.envelopes();

        List<Record> batch = FlinkMappingJob.run(factory(), envelopes, ExecutionMode.BATCH, "run-batch");
        List<Record> streaming =
                FlinkMappingJob.run(factory(), envelopes, ExecutionMode.STREAMING, "run-streaming");

        assertThat(canonicalIds(streaming, "#Person"))
                .as("the same human must get the same cluster in either modality")
                .isEqualTo(canonicalIds(batch, "#Person"));

        // Two of the three rows are the same human, so three Person records carry two clusters.
        assertThat(canonicalIds(batch, "#Person")).hasSize(3).containsAnyElementsOf(
                canonicalIds(streaming, "#Person"));
        assertThat(canonicalIds(batch, "#Person").stream().distinct().toList()).hasSize(2);
    }

    private static List<String> canonicalIds(List<Record> records, String typeSuffix) {
        return records.stream()
                .filter(record -> record.typeName().endsWith(typeSuffix))
                .map(record -> String.valueOf(record.raw("canonicalId")))
                .toList();
    }

    @Test
    @DisplayName("a corrupted record is withheld identically in both modalities")
    void corruptionBehavesIdenticallyInBothModalities() throws Exception {
        String corrupted = CadMappingFixture.ROW_BURGLARY_VICTIM.replace("03/14/1988", "1988-03-14");
        List<RawEnvelope> envelopes = List.of(
                CadMappingFixture.envelope(corrupted, 2),
                CadMappingFixture.envelope(CadMappingFixture.ROW_OTHER_PERSON, 3));

        List<Record> batch = FlinkMappingJob.run(factory(), envelopes, ExecutionMode.BATCH, "run-batch");
        List<Record> streaming =
                FlinkMappingJob.run(factory(), envelopes, ExecutionMode.STREAMING, "run-streaming");

        assertThat(streaming).containsExactlyElementsOf(batch);
        // The corrupted row still yields its Incident; its Person and association are withheld.
        assertThat(batch).hasSize(4);
    }
}
