package gov.niemplatform.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.canonical.core.CoreCanonicalTypes;
import gov.niemplatform.connectors.api.ConnectorConfig;
import gov.niemplatform.connectors.api.ConnectorRegistry;
import gov.niemplatform.connectors.api.ConnectorType;
import gov.niemplatform.connectors.api.HealthStatus;
import gov.niemplatform.connectors.api.InteractionMode;
import gov.niemplatform.connectors.api.RetentionPosture;
import gov.niemplatform.connectors.api.SourceConnector;
import gov.niemplatform.connectors.api.SourceDefinition;
import gov.niemplatform.connectors.api.SourceHandle;
import gov.niemplatform.contracts.HopContract;
import gov.niemplatform.runtime.engine.MappingDefinition;
import gov.niemplatform.runtime.engine.MappingLoader;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The catalogue says how a source arrives, and what that means for keeping it.
 *
 * <p>ADR 0027 requires a connector to declare its interaction mode and retention posture, and says
 * in as many words that a non-retainable source "has to be visible in the catalogue". Declaring a
 * fact only helps if someone who is not reading source files can read it.
 */
class CatalogueArrivalTest {

    /** Stands in for a transport whose retention comes from configuration, as Kafka's does. */
    private static final class ConfiguredRetentionConnector implements SourceConnector {

        private RetentionPosture retention;

        @Override
        public ConnectorType type() {
            return ConnectorType.of("queue");
        }

        @Override
        public InteractionMode interactionMode() {
            return InteractionMode.PUSH;
        }

        @Override
        public RetentionPosture retention() {
            if (retention == null) {
                throw new IllegalStateException("retention() before configure()");
            }
            return retention;
        }

        @Override
        public void configure(ConnectorConfig config) {
            retention = RetentionPosture.valueOf(
                    config.requiredSetting("retention").toUpperCase(java.util.Locale.ROOT));
        }

        @Override
        public SourceHandle open() {
            throw new UnsupportedOperationException("a catalogue never opens a source");
        }

        @Override
        public HealthStatus health() {
            throw new AssertionError(
                    "a catalogue reached for the source; describing must never touch it");
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }

    private static MappingDefinition mapping() throws IOException {
        try (InputStream stream = CatalogueArrivalTest.class
                .getResourceAsStream("/mappings/cad-to-canonical-1.0.0.yaml")) {
            return new MappingLoader().load(stream, "cad-to-canonical-1.0.0.yaml");
        }
    }

    private static SourceDefinition definition(String instance, String type, Map<String, String> settings,
            Duration sla) {
        return new SourceDefinition(
                "riverton-pd-cad", instance, ConnectorType.of(type), settings, sla);
    }

    private static Catalogue.Source catalogueOf(
            List<SourceDefinition> definitions, ConnectorRegistry registry) throws IOException {
        return Catalogue.of(mapping(), Map.<String, HopContract>of(), CoreCanonicalTypes.ALL,
                definitions, registry);
    }

    @Test
    @DisplayName("a retainable source says so, and says replay is available")
    void describesARetainedSource() throws IOException {
        var source = catalogueOf(
                List.of(definition("queue-1", "queue", Map.of("retention", "retained"),
                        Duration.ofMinutes(15))),
                ConnectorRegistry.of(new ConfiguredRetentionConnector()));

        assertThat(source.arrivals()).singleElement().satisfies(arrival -> {
            assertThat(arrival.connectorType()).isEqualTo("queue");
            assertThat(arrival.connectorInstanceId()).isEqualTo("queue-1");
            assertThat(arrival.interactionMode()).isEqualTo("PUSH");
            assertThat(arrival.retention()).isEqualTo("RETAINED");
            assertThat(arrival.replayable()).isTrue();
            assertThat(arrival.freshnessSla()).contains("PT15M");
            assertThat(arrival.described()).isTrue();
        });
        assertThat(source.arrivalUndeclared()).isFalse();
    }

    @Test
    @DisplayName("a non-retainable source is visible as one, and is not offered replay")
    void describesATransientSource() throws IOException {
        var source = catalogueOf(
                List.of(definition("queue-1", "queue", Map.of("retention", "transient"), null)),
                ConnectorRegistry.of(new ConfiguredRetentionConnector()));

        // The consequence ADR 0027 draws: where nothing may be held, there is nothing to replay
        // from, and any surface offering it would be lying.
        assertThat(source.arrivals()).singleElement().satisfies(arrival -> {
            assertThat(arrival.retention()).isEqualTo("TRANSIENT");
            assertThat(arrival.replayable()).isFalse();
        });
    }

    @Test
    @DisplayName("one source arriving by two transports is two arrivals, not one flattened field")
    void keepsEachTransportSeparate() throws IOException {
        var source = catalogueOf(
                List.of(
                        definition("queue-1", "queue", Map.of("retention", "retained"), null),
                        definition("queue-2", "queue", Map.of("retention", "retained"), null)),
                ConnectorRegistry.of(new ConfiguredRetentionConnector()));

        // Riverton CAD arrives as a nightly drop and as a live topic under one mapping. Collapsing
        // the two would hide the separation between transport and meaning that makes it possible.
        assertThat(source.arrivals()).hasSize(2)
                .extracting(Catalogue.Arrival::connectorInstanceId)
                .containsExactly("queue-1", "queue-2");
    }

    @Test
    @DisplayName("a transport this deployment cannot read is listed with its reason, not dropped")
    void listsUndescribableArrivals() throws IOException {
        var source = catalogueOf(
                List.of(definition("cdc-1", "cdc", Map.of(), null)),
                ConnectorRegistry.of(new ConfiguredRetentionConnector()));

        // Silently missing would be indistinguishable from a source nobody configured, and those
        // need different fixes.
        assertThat(source.arrivals()).singleElement().satisfies(arrival -> {
            assertThat(arrival.described()).isFalse();
            assertThat(arrival.problem()).get().asString().contains("no connector for transport cdc");
            assertThat(arrival.replayable()).isFalse();
        });
        assertThat(source.arrivalUndeclared()).isFalse();
    }

    @Test
    @DisplayName("a definition that cannot be configured is reported, not thrown")
    void reportsAConfigurationFailure() throws IOException {
        var source = catalogueOf(
                List.of(definition("queue-1", "queue", Map.of(), null)),
                ConnectorRegistry.of(new ConfiguredRetentionConnector()));

        assertThat(source.arrivals()).singleElement().satisfies(arrival -> {
            assertThat(arrival.described()).isFalse();
            assertThat(arrival.retention()).isEqualTo("undeclared");
        });
    }

    @Test
    @DisplayName("a source nothing describes is a gap, like an undocumented term")
    void reportsAnUndeclaredArrivalAsAGap() throws IOException {
        var source = catalogueOf(List.of(), ConnectorRegistry.of());

        assertThat(source.arrivals()).isEmpty();
        assertThat(source.arrivalUndeclared()).isTrue();
    }

    @Test
    @DisplayName("a definition for another source is not attributed to this one")
    void ignoresOtherSources() throws IOException {
        var other = new SourceDefinition("some-other-cad", "queue-9", ConnectorType.of("queue"),
                Map.of("retention", "retained"), null);

        assertThat(catalogueOf(List.of(other), ConnectorRegistry.of(new ConfiguredRetentionConnector()))
                .arrivals()).isEmpty();
    }

    @Test
    @DisplayName("describing a source never reaches it")
    void neverTouchesTheSource() throws IOException {
        // health() on the stub fails the test if called. Configuring validates settings; reaching
        // the source is a different question, and a catalogue has no business asking it.
        var source = catalogueOf(
                List.of(definition("queue-1", "queue", Map.of("retention", "retained"), null)),
                ConnectorRegistry.of(new ConfiguredRetentionConnector()));

        assertThat(source.arrivals()).hasSize(1);
    }

    @Test
    @DisplayName("a source with no declared freshness reports none rather than inventing one")
    void freshnessIsAbsentWhenUndeclared() throws IOException {
        var source = catalogueOf(
                List.of(definition("queue-1", "queue", Map.of("retention", "retained"), null)),
                ConnectorRegistry.of(new ConfiguredRetentionConnector()));

        // A source with no stated expectation cannot be late, and a default would fill an
        // operator's console with noise they would learn to ignore.
        assertThat(source.arrivals().getFirst().freshnessSla()).isEmpty();
    }
}
