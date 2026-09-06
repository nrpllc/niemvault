package gov.niemplatform.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A source's field shape or cardinality deviated from its rolling baseline (spec §4.7).
 *
 * <p>This is the event the platform exists for. Nothing has failed: records are landing,
 * contracts are passing, the pipeline is green. But a field that was 95% populated is now 40%
 * populated, or a date that was always {@code ####-##-##} is arriving as {@code ##/##/####} in
 * a growing share of records. Left alone that becomes silent corruption in gold.
 *
 * <p>Emitted as a {@link Severity#WARNING} rather than an error: drift is a signal to look, not
 * a reason to stop. Halting a pipeline on a statistical deviation would make the platform less
 * trustworthy, not more.
 *
 * @param context which source drifted
 * @param fieldName field that drifted
 * @param measure what was measured, e.g. {@code nullRate} or {@code shapeDistribution}
 * @param baseline the rolling baseline value, rendered
 * @param observed the current value, rendered
 * @param deviation magnitude of the deviation, in units of the measure
 * @param sampleSize how many records the observation is drawn from
 */
public record SourceDrift(
        PipelineContext context,
        String fieldName,
        String measure,
        String baseline,
        String observed,
        double deviation,
        long sampleSize) implements ObservabilityEvent {

    public SourceDrift {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(fieldName, "fieldName");
        Objects.requireNonNull(measure, "measure");
        if (sampleSize <= 0) {
            throw new IllegalArgumentException("Drift must be observed over at least one record");
        }
    }

    @Override
    public EventType type() {
        return EventType.SOURCE_DRIFT;
    }

    @Override
    public Severity severity() {
        return Severity.WARNING;
    }

    @Override
    public Map<String, Object> attributes() {
        Map<String, Object> attributes = new LinkedHashMap<>(context.attributes());
        attributes.put("field", fieldName);
        attributes.put("measure", measure);
        attributes.put("baseline", baseline);
        attributes.put("observed", observed);
        attributes.put("deviation", deviation);
        attributes.put("sampleSize", sampleSize);
        return attributes;
    }

    @Override
    public String summary() {
        return "source '%s' field '%s' drifted on %s: baseline %s, observed %s over %d records"
                .formatted(context.sourceId(), fieldName, measure, baseline, observed, sampleSize);
    }
}
