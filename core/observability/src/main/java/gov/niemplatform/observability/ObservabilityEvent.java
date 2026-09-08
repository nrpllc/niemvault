package gov.niemplatform.observability;

import java.io.Serializable;
import java.util.Map;

/**
 * A structured observability event (spec §4.7).
 *
 * <p>Structured events, not log strings. The distinction is not stylistic: drift detection
 * needs events that can be counted, grouped, and compared against a baseline, and a log line
 * cannot be. Sinks in GCC High, Azure Government, and air-gapped environments cannot rely on
 * the external tooling that would otherwise parse strings back into structure, so the structure
 * is produced at the source.
 *
 * <p>Sealed so a sink that renders every permitted type renders everything the platform emits.
 * Adding an event kind is a deliberate change to the taxonomy, not an incidental log statement.
 *
 * <p>Events carry no timestamp and no identifier. Those are stamped by the
 * {@link ObservabilityEmitter}, which keeps the events themselves pure values -- comparable in
 * tests, and safe to construct inside a Flink operator without reaching for a clock.
 */
public sealed interface ObservabilityEvent extends Serializable
        permits ContractViolation, SourceDrift, PipelineLag, ResolutionAnomaly, ProjectionDivergence,
        CompletenessBreach {

    /** Which kind of event this is. */
    EventType type();

    /** How urgently it needs a human. */
    Severity severity();

    /** Where in the platform it came from. */
    PipelineContext context();

    /**
     * Event-specific fields, flattened for a structured sink.
     *
     * <p>Must never contain a raw record value. Values reach an event only as a
     * {@link ValueShape}. See ADR 0015.
     */
    Map<String, Object> attributes();

    /** One-line human summary. Values are already redacted by the time they reach here. */
    String summary();
}
