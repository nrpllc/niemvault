package gov.niemplatform.identity.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two rules spec §4.5 states about external providers.
 *
 * <p>Both are easy to satisfy by accident with the bundled resolver, which writes to the
 * platform's index anyway, and easy to violate the moment a real external provider is plugged in.
 * These tests use a provider that behaves like an external one -- it returns its own identifiers
 * and its own internal state -- so the rules are tested where they actually bite.
 */
class IndexedResolutionProviderTest {

    /**
     * Stands in for Senzing or IBM entity analytics: clusters externally, knows nothing about the
     * platform's index, and hands back internal state the platform must not keep.
     */
    private static final class ExternalProvider implements ResolutionProvider {

        private int calls;

        @Override
        public ResolutionResult resolve(EntityAttributes attributes) {
            calls++;
            return ResolutionResult.matched(
                    ClusterId.of("SENZING-ENTITY-" + attributes.attribute("surName").orElse("?")),
                    0.87,
                    List.of(new MatchEvidence(
                            "SENZING_RULE_42",
                            List.of("surName"),
                            List.of(ResolutionKey.of("EXTERNAL", "opaque")),
                            "resolved by the external engine")));
        }

        @Override
        public ProviderCapabilities capabilities() {
            return new ProviderCapabilities("senzing-like", Set.of("Person"),
                    true, true, Set.of("surName"), List.of());
        }
    }

    private static EntityAttributes person(String sourceKey, String surname) {
        return EntityAttributes.of("Person", sourceKey, Map.of("surName", surname));
    }

    @Test
    @DisplayName("the platform records an assignment even when an external provider decides")
    void platformKeepsItsOwnIndex() {
        InMemoryClusterIndex index = new InMemoryClusterIndex(gov.niemplatform.canonical.meta.TenantId.of("test.agency"));
        ResolutionProvider provider = new IndexedResolutionProvider(new ExternalProvider(), index);

        provider.resolve(person("rec-1", "DOE"));

        assertThat(index.assignment("Person", "rec-1")).get()
                .returns(ClusterId.of("SENZING-ENTITY-DOE"), ClusterAssignment::clusterId)
                .returns("senzing-like", ClusterAssignment::providerId)
                .returns(0.87, ClusterAssignment::confidence);
    }

    @Test
    @DisplayName("without the index, cross-domain joins would have nothing to join on")
    void clustersAreDiscoverable() {
        InMemoryClusterIndex index = new InMemoryClusterIndex(gov.niemplatform.canonical.meta.TenantId.of("test.agency"));
        ResolutionProvider provider = new IndexedResolutionProvider(new ExternalProvider(), index);

        provider.resolve(person("rec-1", "DOE"));
        provider.resolve(person("rec-2", "RIVERA"));
        provider.resolve(person("rec-3", "DOE"));

        assertThat(index.clusters("Person")).hasSize(2);
        assertThat(index.clusterCount("Person")).isEqualTo(2);
    }

    @Test
    @DisplayName("only the cluster id, the confidence, and the evidence are kept")
    void nothingBeyondTheContractIsPersisted() {
        InMemoryClusterIndex index = new InMemoryClusterIndex(gov.niemplatform.canonical.meta.TenantId.of("test.agency"));
        ResolutionProvider provider = new IndexedResolutionProvider(new ExternalProvider(), index);

        provider.resolve(person("rec-1", "DOE"));

        ClusterAssignment assignment = index.assignment("Person", "rec-1").orElseThrow();
        // ClusterAssignment has no component for provider state, so this is a structural
        // guarantee rather than a discipline. The test states the requirement so a future
        // component added to that record has to argue with it.
        assertThat(ClusterAssignment.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactlyInAnyOrder(
                        "entityType", "sourceRecordKey", "clusterId", "providerId",
                        "confidence", "evidence");
        assertThat(assignment.evidence()).hasSize(1);
    }

    @Test
    @DisplayName("the wrapper does not change what the provider decided")
    void delegationIsTransparent() {
        ExternalProvider external = new ExternalProvider();
        ResolutionProvider provider = new IndexedResolutionProvider(external, new InMemoryClusterIndex(gov.niemplatform.canonical.meta.TenantId.of("test.agency")));

        ResolutionResult direct = new ExternalProvider().resolve(person("rec-1", "DOE"));
        ResolutionResult wrapped = provider.resolve(person("rec-1", "DOE"));

        assertThat(wrapped.clusterId()).isEqualTo(direct.clusterId());
        assertThat(wrapped.confidence()).isEqualTo(direct.confidence());
        assertThat(provider.capabilities().providerId()).isEqualTo("senzing-like");
        assertThat(external.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("linking a key that already points elsewhere reports the conflict rather than merging")
    void conflictingKeysAreReported() {
        InMemoryClusterIndex index = new InMemoryClusterIndex(gov.niemplatform.canonical.meta.TenantId.of("test.agency"));
        ResolutionKey shared = ResolutionKey.of("NAME_DOB", "DOE|JANE|1988-03-14");

        index.link("Person", ClusterId.of("cluster-a"), List.of(shared));
        List<ResolutionKey> conflicting =
                index.link("Person", ClusterId.of("cluster-b"), List.of(shared));

        assertThat(conflicting).containsExactly(shared);
        assertThat(index.find("Person", shared)).contains(ClusterId.of("cluster-a"));
    }
}
