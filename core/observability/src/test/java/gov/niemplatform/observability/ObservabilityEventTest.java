package gov.niemplatform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The §4.7 event taxonomy, and the redaction rule that keeps it safe to ship.
 *
 * <p>The redaction assertions are the important ones. Every other property here is a
 * convenience; that one is the difference between telemetry and a disclosure.
 */
class ObservabilityEventTest {

    private static final PipelineContext CONTEXT =
            PipelineContext.ofHop("riverton-pd-cad", "run-1", "map-person", "1.0.0");

    @Nested
    @DisplayName("values are described, never disclosed")
    class Redaction {

        @Test
        @DisplayName("a Social Security Number becomes a shape, not a value")
        void ssnIsShaped() {
            ValueShape shape = ValueShape.of("123-45-6789");

            assertThat(shape.pattern()).isEqualTo("###-##-####");
            assertThat(shape.length()).isEqualTo(11);
            assertThat(shape.toString()).doesNotContain("123", "456", "6789");
        }

        @Test
        @DisplayName("format changes stay distinguishable after redaction")
        void formatChangeIsVisible() {
            assertThat(ValueShape.of("2026-03-04").pattern()).isEqualTo("####-##-##");
            assertThat(ValueShape.of("03/04/2026").pattern()).isEqualTo("##/##/####");
            assertThat(ValueShape.of("2026/03/04 11:20").pattern()).isEqualTo("####/##/##_##:##");
        }

        @Test
        @DisplayName("a packed name field is shaped without revealing the name")
        void nameIsShaped() {
            ValueShape shape = ValueShape.of("DOE, JANE M");

            assertThat(shape.pattern()).isEqualTo("AAA,_AAAA_A");
            assertThat(shape.toString()).doesNotContain("DOE", "JANE");
        }

        @Test
        @DisplayName("long values are truncated, since a format change shows up early")
        void longValuesTruncate() {
            ValueShape shape = ValueShape.of("A".repeat(100));

            assertThat(shape.length()).isEqualTo(100);
            assertThat(shape.pattern()).hasSize(33).endsWith("…");
        }

        @Test
        @DisplayName("a missing value is distinguishable from an empty one")
        void absentVersusEmpty() {
            assertThat(ValueShape.of(null)).isEqualTo(ValueShape.absent());
            assertThat(ValueShape.of("").javaType()).isEqualTo("String");
            assertThat(ValueShape.of("").length()).isZero();
        }

        @Test
        @DisplayName("non-string scalars disclose only their type")
        void scalarsDiscloseType() {
            assertThat(ValueShape.of(java.time.LocalDate.of(1988, 3, 14)))
                    .returns("LocalDate", ValueShape::javaType)
                    .returns(null, ValueShape::pattern);
        }

        @Test
        @DisplayName("no raw value reaches a contract violation's attributes")
        void violationAttributesCarryNoValues() {
            ContractViolation violation = new ContractViolation(
                    CONTEXT,
                    Direction.INPUT,
                    "source:cad-csv/person",
                    List.of(new ContractViolation.Failure(
                            "socialSecurityId", "shape", "#########", ValueShape.of("123-45-6789"))),
                    "q-42");

            String rendered = violation.attributes().toString() + violation.summary();

            assertThat(rendered).doesNotContain("123-45-6789", "12345", "6789");
            assertThat(rendered).contains("###-##-####");
        }
    }

    @Nested
    @DisplayName("contract violations")
    class Violations {

        @Test
        @DisplayName("carry the hop, the direction, and every failure")
        void carryFullContext() {
            ContractViolation violation = new ContractViolation(
                    CONTEXT, Direction.OUTPUT, "canonical#Person",
                    List.of(
                            new ContractViolation.Failure("surName", "required", "a value", ValueShape.absent()),
                            new ContractViolation.Failure("sexCode", "codeList", "[M, F, X, U]", ValueShape.of("?"))),
                    "q-7");

            assertThat(violation.type()).isEqualTo(EventType.CONTRACT_VIOLATION);
            assertThat(violation.severity()).isEqualTo(Severity.ERROR);
            assertThat(violation.attributes())
                    .containsEntry("direction", "OUTPUT")
                    .containsEntry("hopId", "map-person")
                    .containsEntry("mappingVersion", "1.0.0")
                    .containsEntry("failureCount", 2)
                    .containsEntry("quarantineId", "q-7");
        }

        @Test
        @DisplayName("a violation with no failures is meaningless and rejected")
        void requiresAtLeastOneFailure() {
            assertThatThrownBy(() -> new ContractViolation(
                    CONTEXT, Direction.INPUT, "canonical#Person", List.of(), "q-1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("pipeline lag")
    class Lag {

        @Test
        @DisplayName("measures staleness against the source timestamp, not ingest time")
        void measuresOverrun() {
            PipelineLag lag = new PipelineLag(
                    PipelineContext.of("riverton-pd-cad", "run-1"),
                    Instant.parse("2026-03-04T10:00:00Z"),
                    Instant.parse("2026-03-04T13:30:00Z"),
                    Duration.ofHours(1));

            assertThat(lag.lag()).isEqualTo(Duration.ofHours(3).plusMinutes(30));
            assertThat(lag.overrun()).isEqualTo(Duration.ofHours(2).plusMinutes(30));
        }

        @Test
        @DisplayName("a source inside its SLA reports no overrun")
        void withinSlaHasNoOverrun() {
            PipelineLag lag = new PipelineLag(
                    PipelineContext.of("riverton-pd-cad", "run-1"),
                    Instant.parse("2026-03-04T13:00:00Z"),
                    Instant.parse("2026-03-04T13:10:00Z"),
                    Duration.ofHours(1));

            assertThat(lag.overrun()).isZero();
        }
    }

    @Nested
    @DisplayName("projection divergence")
    class Divergence {

        @Test
        @DisplayName("reports the signed difference against silver")
        void reportsDifference() {
            ProjectionDivergence divergence = new ProjectionDivergence(
                    PipelineContext.of("riverton-pd-cad", "run-1"), "GRAPH", "Person", 100, 97);

            assertThat(divergence.difference()).isEqualTo(-3);
            assertThat(divergence.severity()).isEqualTo(Severity.ERROR);
            assertThat(divergence.summary()).contains("97", "100", "-3");
        }

        @Test
        @DisplayName("matching counts are not a divergence and are rejected")
        void matchingCountsRejected() {
            assertThatThrownBy(() -> new ProjectionDivergence(
                    PipelineContext.of("s", "r"), "GRAPH", "Person", 100, 100))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("resolution anomaly")
    class Anomaly {

        @Test
        @DisplayName("reports the shift in a tier's share")
        void reportsShift() {
            ResolutionAnomaly anomaly = new ResolutionAnomaly(
                    PipelineContext.of("riverton-pd-cad", "run-1"),
                    "bundled-deterministic", "tier-3", 0.20, 0.65, 4_000);

            assertThat(anomaly.shift()).isEqualTo(0.45, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(anomaly.summary()).contains("tier-3", "bundled-deterministic");
        }

        @Test
        @DisplayName("a share outside [0, 1] is rejected rather than reported")
        void rejectsImpossibleShare() {
            assertThatThrownBy(() -> new ResolutionAnomaly(
                    PipelineContext.of("s", "r"), "p", "tier-1", 0.2, 1.4, 10))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("source drift must be observed over at least one record")
    void driftNeedsASample() {
        assertThatThrownBy(() -> new SourceDrift(
                CONTEXT, "birthDate", "shapeDistribution", "####-##-##", "##/##/####", 0.4, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
