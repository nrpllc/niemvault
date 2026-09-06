package gov.niemplatform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Emitter behaviour, including the guarantee that telemetry never breaks a pipeline. */
class ObservabilityEmitterTest {

    private static final PipelineContext CONTEXT =
            PipelineContext.ofHop("riverton-pd-cad", "run-1", "map-person", "1.0.0");

    private static ContractViolation violation() {
        return new ContractViolation(CONTEXT, Direction.INPUT, "source:cad-csv/person",
                List.of(new ContractViolation.Failure("surName", "required", "a value", ValueShape.absent())),
                "q-1");
    }

    private static PipelineLag lag() {
        return new PipelineLag(PipelineContext.of("riverton-pd-cad", "run-1"),
                Instant.parse("2026-03-04T10:00:00Z"), Instant.parse("2026-03-04T13:00:00Z"),
                Duration.ofHours(1));
    }

    @Test
    @DisplayName("recorded events keep arrival order and get deterministic identifiers")
    void recordsInOrder() {
        var emitter = new RecordingObservabilityEmitter(
                Clock.fixed(Instant.parse("2026-03-04T12:00:00Z"), ZoneOffset.UTC));

        emitter.emit(violation());
        emitter.emit(lag());

        assertThat(emitter.emitted()).extracting(RecordingObservabilityEmitter.Emitted::id)
                .containsExactly("evt-1", "evt-2");
        assertThat(emitter.emitted()).extracting(RecordingObservabilityEmitter.Emitted::occurredAt)
                .containsOnly(Instant.parse("2026-03-04T12:00:00Z"));
    }

    @Test
    @DisplayName("events can be retrieved by kind, which is how tests assert on them")
    void filtersByType() {
        var emitter = new RecordingObservabilityEmitter();

        emitter.emit(violation());
        emitter.emit(lag());
        emitter.emit(violation());

        assertThat(emitter.eventsOfType(ContractViolation.class)).hasSize(2);
        assertThat(emitter.countOfType(EventType.PIPELINE_LAG)).isEqualTo(1);
        assertThat(emitter.countOfType(EventType.SOURCE_DRIFT)).isZero();
    }

    @Test
    @DisplayName("a composite emitter delivers to every sink")
    void compositeFansOut() {
        var first = new RecordingObservabilityEmitter();
        var second = new RecordingObservabilityEmitter();

        ObservabilityEmitter.composite(first, second).emit(violation());

        assertThat(first.emitted()).hasSize(1);
        assertThat(second.emitted()).hasSize(1);
    }

    @Test
    @DisplayName("emitting never throws, because telemetry must not fail a pipeline")
    void emittingNeverThrows() {
        var recording = new RecordingObservabilityEmitter();
        var logging = new LoggingObservabilityEmitter();

        assertThatCode(() -> {
            recording.emit(null);
            logging.emit(null);
            logging.emit(violation());
            logging.emit(lag());
            ObservabilityEmitter.discarding().emit(violation());
        }).doesNotThrowAnyException();

        assertThat(recording.emitted()).isEmpty();
    }

    @Test
    @DisplayName("clear discards recorded events between phases of a test")
    void clearResets() {
        var emitter = new RecordingObservabilityEmitter();
        emitter.emit(violation());

        emitter.clear();

        assertThat(emitter.emitted()).isEmpty();
    }
}
