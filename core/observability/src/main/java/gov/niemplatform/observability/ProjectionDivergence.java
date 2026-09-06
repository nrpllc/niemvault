package gov.niemplatform.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A projection's counts diverged from what silver implies (spec §4.7).
 *
 * <p>Gold is derived, so it is checkable: the number of Person nodes in the graph should equal
 * the number of distinct Person clusters in silver. When it does not, the projection has
 * silently lost or duplicated something, and every query an investigator runs against it is
 * quietly wrong.
 *
 * <p>Severity is {@link Severity#ERROR}, not warning. Unlike source drift, this is not a signal
 * to look -- it is a known-wrong projection.
 *
 * @param context which run produced the divergence
 * @param projectionType projection at fault, e.g. {@code GRAPH}
 * @param entityName what was counted, e.g. {@code Person} or {@code PersonIncidentAssociation}
 * @param expectedCount count implied by silver
 * @param actualCount count observed in the projection
 */
public record ProjectionDivergence(
        PipelineContext context,
        String projectionType,
        String entityName,
        long expectedCount,
        long actualCount) implements ObservabilityEvent {

    public ProjectionDivergence {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(projectionType, "projectionType");
        Objects.requireNonNull(entityName, "entityName");
        if (expectedCount == actualCount) {
            throw new IllegalArgumentException(
                    "A projection divergence requires differing counts; both were " + expectedCount);
        }
    }

    /** Signed difference. Negative means the projection is missing rows. */
    public long difference() {
        return actualCount - expectedCount;
    }

    @Override
    public EventType type() {
        return EventType.PROJECTION_DIVERGENCE;
    }

    @Override
    public Severity severity() {
        return Severity.ERROR;
    }

    @Override
    public Map<String, Object> attributes() {
        Map<String, Object> attributes = new LinkedHashMap<>(context.attributes());
        attributes.put("projectionType", projectionType);
        attributes.put("entity", entityName);
        attributes.put("expectedCount", expectedCount);
        attributes.put("actualCount", actualCount);
        attributes.put("difference", difference());
        return attributes;
    }

    @Override
    public String summary() {
        return "%s projection has %d %s, silver implies %d (difference %+d)"
                .formatted(projectionType, actualCount, entityName, expectedCount, difference());
    }
}
