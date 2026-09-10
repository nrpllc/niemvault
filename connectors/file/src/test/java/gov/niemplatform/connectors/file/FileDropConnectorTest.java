package gov.niemplatform.connectors.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.connectors.api.ConnectorConfig;
import gov.niemplatform.connectors.api.ConnectorConfigurationException;
import gov.niemplatform.connectors.api.ConnectorRegistry;
import gov.niemplatform.connectors.api.HealthStatus;
import gov.niemplatform.connectors.api.LandingService;
import gov.niemplatform.connectors.api.SourceConnector;
import gov.niemplatform.connectors.api.SourceHandle;
import gov.niemplatform.observability.EventType;
import gov.niemplatform.observability.RecordingObservabilityEmitter;
import gov.niemplatform.storage.api.BronzeRange;
import gov.niemplatform.storage.api.RawEnvelope;
import gov.niemplatform.storage.parquet.ParquetBronzeStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The file drop connector and the landing boundary it feeds (spec §4.3, §4.4).
 *
 * <p>The property under test throughout is that nothing transport-shaped survives past
 * {@code RawEnvelope}, and that the connector stays ignorant of what the data means -- it cuts
 * files into records and stops. Parsing is the mapping's job, and keeping that line sharp is what
 * lets one mapping serve a future Kafka feed of the same records.
 */
class FileDropConnectorTest {

    private static final Instant NOW = Instant.parse("2026-03-04T17:25:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final String INCIDENTS_CSV = """
            INC_NUM,CALL_TYPE,RPT_DTTM,ADDR,BEAT
            2026-000114,BURG,2026/03/04 11:20,"418 W 9TH ST",3A
            2026-000115,THEFT,2026/03/04 12:05,"1200 MAIN ST",2B
            """;

    @TempDir
    Path drop;

    @TempDir
    Path bronzeRoot;

    private ConnectorConfig config(Map<String, String> settings) {
        return ConnectorConfig.of("riverton-pd-cad", "file-drop-01", FileDropConnector.TYPE, settings);
    }

    private ConnectorConfig defaultConfig() {
        return config(Map.of(
                "directory", drop.toString(),
                "filePattern", "*.csv",
                "skipHeaderLines", "1"));
    }

    private FileDropConnector configured(ConnectorConfig config) {
        FileDropConnector connector = new FileDropConnector(FIXED);
        connector.configure(config);
        return connector;
    }

    private void writeDrop(String fileName, String content) throws IOException {
        Files.writeString(drop.resolve(fileName), content, StandardCharsets.UTF_8);
    }

    private List<RawEnvelope> readAll(FileDropConnector connector) {
        try (SourceHandle handle = connector.open();
                Stream<RawEnvelope> envelopes = handle.envelopes()) {
            return envelopes.toList();
        }
    }

    @Test
    @DisplayName("each data line becomes one envelope, with the header skipped")
    void oneEnvelopePerDataLine() throws IOException {
        writeDrop("incidents.csv", INCIDENTS_CSV);

        List<RawEnvelope> landed = readAll(configured(defaultConfig()));

        assertThat(landed).hasSize(2);
        assertThat(landed).extracting(RawEnvelope::payloadAsText)
                .containsExactly(
                        "2026-000114,BURG,2026/03/04 11:20,\"418 W 9TH ST\",3A",
                        "2026-000115,THEFT,2026/03/04 12:05,\"1200 MAIN ST\",2B");
    }

    @Test
    @DisplayName("the connector does not parse: the payload is the raw line, quotes and all")
    void payloadIsUnparsed() throws IOException {
        writeDrop("incidents.csv", INCIDENTS_CSV);

        RawEnvelope first = readAll(configured(defaultConfig())).getFirst();

        assertThat(first.payloadAsText()).contains("\"418 W 9TH ST\"").contains("2026/03/04 11:20");
    }

    @Test
    @DisplayName("offsets identify the physical line and sort in file order")
    void offsetsAreStableAndOrdered() throws IOException {
        writeDrop("incidents.csv", INCIDENTS_CSV);

        List<RawEnvelope> landed = readAll(configured(defaultConfig()));

        assertThat(landed).extracting(envelope -> envelope.offset().value())
                .containsExactly("incidents.csv#000002", "incidents.csv#000003")
                .isSorted();
    }

    @Test
    @DisplayName("files are read in name order, so a run is reproducible")
    void filesReadInNameOrder() throws IOException {
        writeDrop("b-second.csv", "H\nbeta\n");
        writeDrop("a-first.csv", "H\nalpha\n");

        List<RawEnvelope> landed = readAll(configured(defaultConfig()));

        assertThat(landed).extracting(RawEnvelope::payloadAsText).containsExactly("alpha", "beta");
    }

    @Test
    @DisplayName("blank lines are skipped without disturbing line numbering")
    void blankLinesSkipped() throws IOException {
        writeDrop("incidents.csv", "HEADER\nfirst\n\n\nsecond\n");

        List<RawEnvelope> landed = readAll(configured(defaultConfig()));

        assertThat(landed).extracting(envelope -> envelope.offset().value())
                .containsExactly("incidents.csv#000002", "incidents.csv#000005");
    }

    @Test
    @DisplayName("only files matching the pattern are picked up")
    void patternFilters() throws IOException {
        writeDrop("incidents.csv", "H\nrow\n");
        writeDrop("notes.txt", "H\nignored\n");

        assertThat(readAll(configured(defaultConfig())))
                .extracting(RawEnvelope::payloadAsText).containsExactly("row");
    }

    @Test
    @DisplayName("file mode yields one envelope carrying the whole document")
    void fileModeYieldsWholeFile() throws IOException {
        writeDrop("incidents.csv", INCIDENTS_CSV);

        List<RawEnvelope> landed = readAll(configured(config(Map.of(
                "directory", drop.toString(),
                "filePattern", "*.csv",
                "recordMode", "file"))));

        assertThat(landed).singleElement().satisfies(envelope -> {
            assertThat(envelope.payloadAsText()).isEqualTo(INCIDENTS_CSV);
            assertThat(envelope.offset().value()).isEqualTo("incidents.csv");
        });
    }

    @Test
    @DisplayName("an empty drop directory yields nothing and is not an error")
    void emptyDirectoryIsFine() {
        assertThat(readAll(configured(defaultConfig()))).isEmpty();
    }

    @Nested
    @DisplayName("configuration is validated on load")
    class Configuration {

        @Test
        @DisplayName("a misspelled setting is rejected rather than silently ignored")
        void misspelledSettingRejected() {
            ConnectorConfig bad = config(Map.of("directroy", drop.toString()));

            assertThatThrownBy(() -> configured(bad))
                    .isInstanceOf(ConnectorConfigurationException.class)
                    .satisfies(thrown -> assertThat(
                            ((ConnectorConfigurationException) thrown).problems())
                            .extracting(ConnectorConfigurationException.Problem::settingKey)
                            .contains("directroy"));
        }

        @Test
        @DisplayName("a missing directory is rejected")
        void missingDirectoryRejected() {
            assertThatThrownBy(() -> configured(config(Map.of("filePattern", "*.csv"))))
                    .isInstanceOf(ConnectorConfigurationException.class);
        }

        // A directory that does not exist used to be refused here. It is now a health question --
        // configuring asks whether the settings are well-formed, and only health asks whether the
        // source can be reached. See ConfiguringIsNotReachingTest, which asserts both halves.

        @Test
        @DisplayName("every configuration problem is reported at once")
        void allProblemsAtOnce() {
            ConnectorConfig bad = config(Map.of(
                    "directory", drop.toString(),
                    "recordMode", "sideways",
                    "charset", "NOT-A-CHARSET"));

            assertThatThrownBy(() -> configured(bad))
                    .isInstanceOf(ConnectorConfigurationException.class)
                    .satisfies(thrown -> assertThat(
                            ((ConnectorConfigurationException) thrown).problems()).hasSize(2));
        }

        @Test
        @DisplayName("settings are never printed, only their keys")
        void settingsAreNotPrinted() {
            ConnectorConfig withSecret = config(Map.of(
                    "directory", drop.toString(), "charset", "UTF-8"));

            assertThat(withSecret.toString())
                    .contains("directory", "charset")
                    .doesNotContain(drop.toString());
        }

        @Test
        @DisplayName("opening before configuring is a programming error, not a silent no-op")
        void openBeforeConfigure() {
            assertThatThrownBy(() -> new FileDropConnector(FIXED).open())
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("health reports reachability, not activity")
    class Health {

        @Test
        @DisplayName("an unconfigured connector says so")
        void unconfigured() {
            assertThat(new FileDropConnector(FIXED).health().state())
                    .isEqualTo(HealthStatus.State.NOT_CONFIGURED);
        }

        @Test
        @DisplayName("an empty but reachable directory is healthy, not degraded")
        void emptyIsHealthy() {
            assertThat(configured(defaultConfig()).health().isHealthy()).isTrue();
        }

        @Test
        @DisplayName("a directory that disappears becomes unavailable")
        void vanishedDirectoryIsUnavailable() throws IOException {
            FileDropConnector connector = configured(defaultConfig());
            Files.delete(drop);

            HealthStatus health = connector.health();

            assertThat(health.state()).isEqualTo(HealthStatus.State.UNAVAILABLE);
            assertThat(health.detail()).contains("no longer exists");
        }
    }

    @Nested
    @DisplayName("landing into bronze")
    class Landing {

        @Test
        @DisplayName("a connector run lands byte-preserved records in bronze")
        void landsIntoBronze() throws IOException {
            writeDrop("incidents.csv", INCIDENTS_CSV);
            var emitter = new RecordingObservabilityEmitter();

            try (ParquetBronzeStore bronze = new ParquetBronzeStore(bronzeRoot, gov.niemplatform.canonical.meta.TenantId.of("test.agency"), FIXED)) {
                var landing = new LandingService(bronze, emitter, FIXED, 1_000);
                var result = landing.land(configured(defaultConfig()), defaultConfig(), "run-1");

                assertThat(result.recordsLanded()).isEqualTo(2);
                assertThat(result.receipts()).hasSize(1);

                try (Stream<RawEnvelope> read = bronze.read("riverton-pd-cad", BronzeRange.all())) {
                    assertThat(read.toList()).extracting(RawEnvelope::payloadAsText)
                            .containsExactly(
                                    "2026-000114,BURG,2026/03/04 11:20,\"418 W 9TH ST\",3A",
                                    "2026-000115,THEFT,2026/03/04 12:05,\"1200 MAIN ST\",2B");
                }
            }
        }

        @Test
        @DisplayName("landing commits in batches, bounding the blast radius of a crash")
        void commitsInBatches() throws IOException {
            writeDrop("incidents.csv", "HEADER\na\nb\nc\nd\ne\n");
            var emitter = new RecordingObservabilityEmitter();

            try (ParquetBronzeStore bronze = new ParquetBronzeStore(bronzeRoot, gov.niemplatform.canonical.meta.TenantId.of("test.agency"), FIXED)) {
                var landing = new LandingService(bronze, emitter, FIXED, 2);
                var result = landing.land(configured(defaultConfig()), defaultConfig(), "run-1");

                assertThat(result.recordsLanded()).isEqualTo(5);
                assertThat(result.receipts()).hasSize(3);
                assertThat(bronze.batches("riverton-pd-cad")).hasSize(3);
            }
        }

        @Test
        @DisplayName("a source past its declared freshness SLA reports lag")
        void staleSourceReportsLag() throws IOException {
            writeDrop("incidents.csv", INCIDENTS_CSV);
            Files.setLastModifiedTime(drop.resolve("incidents.csv"),
                    java.nio.file.attribute.FileTime.from(NOW.minus(Duration.ofHours(6))));

            ConnectorConfig withSla = new ConnectorConfig(
                    "riverton-pd-cad", "file-drop-01", FileDropConnector.TYPE,
                    Map.of("directory", drop.toString(), "filePattern", "*.csv", "skipHeaderLines", "1"),
                    Duration.ofHours(1));

            var emitter = new RecordingObservabilityEmitter();
            try (ParquetBronzeStore bronze = new ParquetBronzeStore(bronzeRoot, gov.niemplatform.canonical.meta.TenantId.of("test.agency"), FIXED)) {
                new LandingService(bronze, emitter, FIXED, 1_000)
                        .land(configured(withSla), withSla, "run-1");
            }

            assertThat(emitter.countOfType(EventType.PIPELINE_LAG)).isEqualTo(1);
        }

        @Test
        @DisplayName("a source with no declared SLA cannot be late")
        void noSlaNoLagEvent() throws IOException {
            writeDrop("incidents.csv", INCIDENTS_CSV);
            Files.setLastModifiedTime(drop.resolve("incidents.csv"),
                    java.nio.file.attribute.FileTime.from(NOW.minus(Duration.ofDays(30))));

            var emitter = new RecordingObservabilityEmitter();
            try (ParquetBronzeStore bronze = new ParquetBronzeStore(bronzeRoot, gov.niemplatform.canonical.meta.TenantId.of("test.agency"), FIXED)) {
                new LandingService(bronze, emitter, FIXED, 1_000)
                        .land(configured(defaultConfig()), defaultConfig(), "run-1");
            }

            assertThat(emitter.emitted()).isEmpty();
        }
    }

    @Test
    @DisplayName("the connector is discoverable through the service loader")
    void discoverableViaSpi() {
        ConnectorRegistry registry = ConnectorRegistry.discover(ServiceLoader.load(SourceConnector.class));

        assertThat(registry.availableTypes()).contains(FileDropConnector.TYPE);
        assertThat(registry.forType(FileDropConnector.TYPE)).get()
                .isInstanceOf(FileDropConnector.class);
    }
}
