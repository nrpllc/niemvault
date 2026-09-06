package gov.niemplatform.observability;

/**
 * Where observability events go (spec §4.7).
 *
 * <p>An emitter stamps each event with an identifier and a timestamp, so events themselves stay
 * pure values -- comparable in tests, and constructible inside a Flink operator without reaching
 * for a clock.
 *
 * <p><strong>Emitting must never throw.</strong> Every call site is on a record-processing path,
 * and a telemetry sink that can fail a pipeline turns observability into a liability. An
 * implementation that cannot deliver an event drops it and carries on.
 *
 * <p>Implementations must work with no external service available. Spec §6 makes air-gapped
 * delivery mandatory, and §4.7 makes drift detection first-class -- together those rule out an
 * emitter that only functions when a hosted collector is reachable.
 */
@FunctionalInterface
public interface ObservabilityEmitter {

    /** Records an event. Never throws. */
    void emit(ObservabilityEvent event);

    /** An emitter that discards everything, for code paths where telemetry is not wanted. */
    static ObservabilityEmitter discarding() {
        return event -> {};
    }

    /**
     * Fans one event out to several sinks. A failure in one sink does not prevent the others
     * from receiving the event.
     */
    static ObservabilityEmitter composite(ObservabilityEmitter... sinks) {
        ObservabilityEmitter[] copy = sinks.clone();
        return event -> {
            for (ObservabilityEmitter sink : copy) {
                sink.emit(event);
            }
        };
    }
}
