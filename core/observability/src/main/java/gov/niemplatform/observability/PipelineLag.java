package gov.niemplatform.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A source's freshness fell behind its declared SLA (spec §4.7).
 *
 * <p>Measured against the source-asserted timestamp on the bronze envelope, not against ingest
 * time. A connector that is happily landing records a day late is on time by ingest time and
 * a day stale by the only measure an investigator cares about.
 *
 * @param context which source is lagging
 * @param latestSourceTimestamp most recent source-asserted timestamp seen
 * @param observedAt when freshness was evaluated
 * @param declaredSla the freshness the source's configuration promises
 */
public record PipelineLag(
        PipelineContext context,
        Instant latestSourceTimestamp,
        Instant observedAt,
        Duration declaredSla) implements ObservabilityEvent {

    public PipelineLag {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(latestSourceTimestamp, "latestSourceTimestamp");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(declaredSla, "declaredSla");
    }

    /** How far behind the source actually is. */
    public Duration lag() {
        return Duration.between(latestSourceTimestamp, observedAt);
    }

    /** How far past the declared SLA the lag has gone. Never negative. */
    public Duration overrun() {
        Duration overrun = lag().minus(declaredSla);
        return overrun.isNegative() ? Duration.ZERO : overrun;
    }

    @Override
    public EventType type() {
        return EventType.PIPELINE_LAG;
    }

    @Override
    public Severity severity() {
        return Severity.WARNING;
    }

    @Override
    public Map<String, Object> attributes() {
        Map<String, Object> attributes = new LinkedHashMap<>(context.attributes());
        attributes.put("latestSourceTimestamp", latestSourceTimestamp.toString());
        attributes.put("observedAt", observedAt.toString());
        attributes.put("declaredSlaSeconds", declaredSla.toSeconds());
        attributes.put("lagSeconds", lag().toSeconds());
        attributes.put("overrunSeconds", overrun().toSeconds());
        return attributes;
    }

    @Override
    public String summary() {
        return "source '%s' is %ds stale against a %ds SLA"
                .formatted(context.sourceId(), lag().toSeconds(), declaredSla.toSeconds());
    }
}
