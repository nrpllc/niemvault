package gov.niemplatform.connectors.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A source is described in a file so that adding a transport does not change a command line. */
class SourceDefinitionTest {

    @TempDir
    Path directory;

    private Path write(String yaml) throws IOException {
        Path file = directory.resolve("source.yaml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("a definition becomes the connector config, settings and freshness intact")
    void loadsATransportDefinition() throws IOException {
        Path file = write("""
                sourceId: riverton-cad
                connectorInstanceId: kafka-1
                type: kafka
                freshnessSla: PT15M
                settings:
                  bootstrapServers: broker:9092
                  topic: cad.incidents
                  groupId: niem-ingest
                  retention: retained
                  maxRecords: 5000
                """);

        SourceDefinition definition = SourceDefinition.load(file);

        assertThat(definition.sourceId()).isEqualTo("riverton-cad");
        assertThat(definition.type()).isEqualTo(ConnectorType.of("kafka"));
        assertThat(definition.declaredFreshnessSla()).contains(Duration.ofMinutes(15));

        ConnectorConfig config = definition.toConnectorConfig();
        // Numbers survive as text: a connector reads its own settings and decides what they mean.
        assertThat(config.requiredSetting("maxRecords")).isEqualTo("5000");
        assertThat(config.declaredFreshnessSla()).contains(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("an unrecognised key is an error, because a silently ignored one is how retention goes missing")
    void refusesUnrecognisedKeys() throws IOException {
        Path file = write("""
                sourceId: riverton-cad
                connectorInstanceId: kafka-1
                type: kafka
                setting:
                  topic: cad.incidents
                """);

        assertThatThrownBy(() -> SourceDefinition.load(file))
                .isInstanceOf(SourceDefinitionException.class)
                .hasMessageContaining("unrecognised key 'setting'");
    }

    @Test
    @DisplayName("every problem is reported at once")
    void reportsEveryProblemTogether() throws IOException {
        Path file = write("""
                connectorInstanceId: kafka-1
                freshnessSla: fifteen minutes
                settings:
                  topic: cad.incidents
                """);

        assertThatThrownBy(() -> SourceDefinition.load(file))
                .isInstanceOf(SourceDefinitionException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.throwable(
                        SourceDefinitionException.class))
                .extracting(SourceDefinitionException::problems)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .hasSize(3)
                .anySatisfy(problem -> assertThat(problem).contains("'sourceId' is required"))
                .anySatisfy(problem -> assertThat(problem).contains("'type' is required"))
                .anySatisfy(problem -> assertThat(problem).contains("ISO-8601"));
    }

    @Test
    @DisplayName("a transport this deployment cannot read says so, and says what it can read")
    void namesTheTransportsItDoesHave() throws IOException {
        Path file = write("""
                sourceId: riverton-cad
                connectorInstanceId: cdc-1
                type: cdc
                settings: {}
                """);
        SourceDefinition definition = SourceDefinition.load(file);

        assertThatThrownBy(() -> definition.connectorFrom(ConnectorRegistry.of(new StubConnector())))
                .isInstanceOf(SourceDefinitionException.class)
                .hasMessageContaining("no connector for transport 'cdc'")
                // An air-gapped operator needs to know whether they are missing a jar or have
                // misspelled a transport, and only the list tells them which.
                .hasMessageContaining("[file-drop]");
    }

    @Test
    @DisplayName("a definition prints setting keys and never values")
    void neverPrintsSettingValues() throws IOException {
        Path file = write("""
                sourceId: riverton-cad
                connectorInstanceId: kafka-1
                type: kafka
                settings:
                  saslJaasConfig: 'password="hunter2";'
                """);

        assertThat(SourceDefinition.load(file).toString())
                .contains("saslJaasConfig")
                .doesNotContain("hunter2");
    }

    /** Stands in for whatever this deployment happens to ship. */
    private static final class StubConnector implements SourceConnector {

        @Override
        public ConnectorType type() {
            return ConnectorType.of("file-drop");
        }

        @Override
        public InteractionMode interactionMode() {
            return InteractionMode.POLL;
        }

        @Override
        public RetentionPosture retention() {
            return RetentionPosture.RETAINED;
        }

        @Override
        public void configure(ConnectorConfig config) {
            // Nothing to configure.
        }

        @Override
        public SourceHandle open() {
            throw new UnsupportedOperationException("not opened in this test");
        }

        @Override
        public HealthStatus health() {
            return HealthStatus.healthy(Instant.now());
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }
}
