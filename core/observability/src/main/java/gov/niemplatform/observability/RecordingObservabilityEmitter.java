package gov.niemplatform.observability;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An in-memory emitter that keeps what it was given.
 *
 * <p>Phase 1 requires observability events to be emitted <em>and asserted in tests</em>, which
 * needs a sink that can be queried afterwards. It also backs the operator CLI's {@code inspect}
 * command for a single run, so this is a working emitter rather than a test double.
 *
 * <p>Thread-safe: events arrive from Flink operator threads, which may be many.
 *
 * <p>Identifiers are sequential per emitter rather than random, so a test asserting on emitted
 * events is reproducible.
 */
public final class RecordingObservabilityEmitter implements ObservabilityEmitter {

    /**
     * An event with the identity and time the emitter stamped on it.
     *
     * @param id sequential within this emitter
     * @param occurredAt when the emitter received it
     * @param event the event itself
     */
    public record Emitted(String id, Instant occurredAt, ObservabilityEvent event) {}

    private final List<Emitted> events = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Clock clock;

    public RecordingObservabilityEmitter() {
        this(Clock.systemUTC());
    }

    /** For tests that need deterministic timestamps. */
    public RecordingObservabilityEmitter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void emit(ObservabilityEvent event) {
        if (event == null) {
            return;
        }
        events.add(new Emitted(
                "evt-" + sequence.incrementAndGet(),
                clock.instant(),
                event));
    }

    /** Everything emitted so far, in arrival order. */
    public List<Emitted> emitted() {
        return List.copyOf(events);
    }

    /** Every emitted event of one kind, in arrival order. */
    public <T extends ObservabilityEvent> List<T> eventsOfType(Class<T> eventType) {
        List<T> matched = new ArrayList<>();
        for (Emitted emitted : events) {
            if (eventType.isInstance(emitted.event())) {
                matched.add(eventType.cast(emitted.event()));
            }
        }
        return List.copyOf(matched);
    }

    /** How many events of a given kind were emitted. */
    public long countOfType(EventType type) {
        return events.stream().filter(e -> e.event().type() == type).count();
    }

    /** Discards everything recorded, e.g. between phases of a test. */
    public void clear() {
        events.clear();
    }
}
