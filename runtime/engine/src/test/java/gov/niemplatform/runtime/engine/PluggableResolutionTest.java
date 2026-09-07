package gov.niemplatform.runtime.engine;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.canonical.core.Person;
import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.contracts.QuarantineSink;
import gov.niemplatform.identity.api.ClusterIndex;
import gov.niemplatform.identity.api.IndexedResolutionProvider;
import gov.niemplatform.identity.api.InMemoryClusterIndex;
import gov.niemplatform.identity.api.ResolutionProvider;
import gov.niemplatform.identity.internal.DeterministicResolutionProvider;
import gov.niemplatform.observability.ObservabilityEmitter;
import gov.niemplatform.observability.RecordingObservabilityEmitter;
import gov.niemplatform.storage.api.RawEnvelope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The resolution SPI is genuinely pluggable (spec §4.5).
 *
 * <p>{@link MappingPipelineTest} exercises the mapping against a test double, as spec §8 requires.
 * This runs the <em>same mapping definition</em> against the bundled resolver instead. Nothing in
 * the mapping, the contracts, or the engine changes -- which is what "agencies with existing
 * investment plug theirs in behind this interface" has to mean in practice.
 *
 * <p>Also checks the rule that is easiest to lose: the platform keeps its own cluster index even
 * though the provider did the resolving, because without it cross-domain joins in the graph are
 * impossible.
 */
class PluggableResolutionTest {

    private static MappingPipeline pipelineWith(ResolutionProvider provider, ObservabilityEmitter emitter) {
        return new MappingPipeline(
                CadMappingFixture.mapping(),
                CadMappingFixture.contracts(),
                Map.of("bundled-deterministic", provider),
                CadMappingFixture.canonicalTypes(),
                new QuarantineSink.InMemory(),
                emitter);
    }

    private static List<String> personClusters(MappingPipeline pipeline) {
        return CadMappingFixture.envelopes().stream()
                .flatMap(envelope -> pipeline.process(envelope, "run-1").canonicalRecords().stream())
                .filter(record -> record.typeName().endsWith("#Person"))
                .map(record -> Person.fromRecord(record).canonicalId().value())
                .toList();
    }

    @Test
    @DisplayName("the bundled resolver drops into the same mapping unchanged")
    void bundledResolverIsInterchangeable() {
        ClusterIndex index = new InMemoryClusterIndex();
        MappingPipeline pipeline = pipelineWith(
                new DeterministicResolutionProvider(index), new RecordingObservabilityEmitter());

        List<String> clusters = personClusters(pipeline);

        assertThat(clusters).hasSize(3);
        // Rows 1 and 3 are the same human; row 2 is not.
        assertThat(clusters.get(0)).isEqualTo(clusters.get(2));
        assertThat(clusters.get(1)).isNotEqualTo(clusters.get(0));
    }

    @Test
    @DisplayName("the platform's index records what the provider decided")
    void platformKeepsItsIndex() {
        InMemoryClusterIndex index = new InMemoryClusterIndex();
        ResolutionProvider provider = new IndexedResolutionProvider(
                new DeterministicResolutionProvider(index), index);
        MappingPipeline pipeline = pipelineWith(provider, ObservabilityEmitter.discarding());

        personClusters(pipeline);

        assertThat(index.clusterCount("Person"))
                .as("three person records, two distinct humans")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("swapping the resolver changes identities but nothing else about the output")
    void onlyIdentitiesDifferBetweenResolvers() {
        MappingPipeline withBundled = pipelineWith(
                new DeterministicResolutionProvider(new InMemoryClusterIndex()),
                ObservabilityEmitter.discarding());
        MappingPipeline withDouble = pipelineWith(
                new CadMappingFixture.TestDoubleResolver(), ObservabilityEmitter.discarding());

        RawEnvelope envelope = CadMappingFixture.envelope(CadMappingFixture.ROW_BURGLARY_VICTIM, 2);
        Record bundledPerson = personRecord(withBundled, envelope);
        Record doublePerson = personRecord(withDouble, envelope);

        assertThat(withoutIdentity(doublePerson))
                .as("every canonical field but the identity is the mapping's work, not the resolver's")
                .isEqualTo(withoutIdentity(bundledPerson));
    }

    private static Record personRecord(MappingPipeline pipeline, RawEnvelope envelope) {
        return pipeline.process(envelope, "run-1").canonicalRecords().stream()
                .filter(record -> record.typeName().endsWith("#Person"))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> withoutIdentity(Record record) {
        return record.values().entrySet().stream()
                .filter(entry -> !entry.getKey().equals("canonicalId"))
                .collect(java.util.LinkedHashMap::new,
                        (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                        java.util.LinkedHashMap::putAll);
    }
}
