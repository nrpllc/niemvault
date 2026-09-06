package gov.niemplatform.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The confidence distribution of identity resolution shifted (spec §4.7).
 *
 * <p>A resolver that starts matching a much larger share of records at its weakest tier is
 * either seeing different data or behaving differently, and both are worth knowing before the
 * graph fills with merged clusters that should not have merged.
 *
 * <p>With the deterministic Phase 1 resolver (ADR 0014) confidence takes only four discrete
 * values, so this detects a shift in the <em>mix of tiers</em> rather than a genuine
 * distribution shift. That is a real limitation, and it is why this event becomes considerably
 * more useful when a probabilistic resolver lands.
 *
 * @param context which source's resolution shifted
 * @param providerId resolver that produced the results
 * @param tier the rule tier or confidence band whose share moved
 * @param baselineShare share of resolutions in this band over the rolling baseline, 0..1
 * @param observedShare share of resolutions in this band now, 0..1
 * @param sampleSize how many resolutions the observation is drawn from
 */
public record ResolutionAnomaly(
        PipelineContext context,
        String providerId,
        String tier,
        double baselineShare,
        double observedShare,
        long sampleSize) implements ObservabilityEvent {

    public ResolutionAnomaly {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(tier, "tier");
        requireShare(baselineShare, "baselineShare");
        requireShare(observedShare, "observedShare");
        if (sampleSize <= 0) {
            throw new IllegalArgumentException("A resolution anomaly must be observed over at least one resolution");
        }
    }

    private static void requireShare(double value, String name) {
        if (value < 0.0 || value > 1.0 || Double.isNaN(value)) {
            throw new IllegalArgumentException(name + " must be a share in [0, 1], found " + value);
        }
    }

    /** Signed change in this band's share. Positive means the band grew. */
    public double shift() {
        return observedShare - baselineShare;
    }

    @Override
    public EventType type() {
        return EventType.RESOLUTION_ANOMALY;
    }

    @Override
    public Severity severity() {
        return Severity.WARNING;
    }

    @Override
    public Map<String, Object> attributes() {
        Map<String, Object> attributes = new LinkedHashMap<>(context.attributes());
        attributes.put("providerId", providerId);
        attributes.put("tier", tier);
        attributes.put("baselineShare", baselineShare);
        attributes.put("observedShare", observedShare);
        attributes.put("shift", shift());
        attributes.put("sampleSize", sampleSize);
        return attributes;
    }

    @Override
    public String summary() {
        return "resolver '%s' tier '%s' moved from %.1f%% to %.1f%% of resolutions over %d samples"
                .formatted(providerId, tier, baselineShare * 100, observedShare * 100, sampleSize);
    }
}
