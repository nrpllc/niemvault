package gov.niemplatform.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Records that landed and were never accounted for (spec §4.7).
 *
 * <p>The one failure mode the rest of the taxonomy cannot see. {@link ContractViolation} reports a
 * record the platform <em>knowingly</em> rejected and {@link ProjectionDivergence} reports counts
 * disagreeing downstream of silver. Neither can tell you that a record landed in bronze and then
 * simply stopped existing, because nothing in either path is obliged to say anything about a record
 * that no longer exists to be talked about.
 *
 * <p>It hides behind a deliberate design decision. §4.2 requires bad data not to halt the pipeline,
 * so quarantining is routine and expected — which means "fewer canonical records than there should
 * be" is indistinguishable from a normal run over a messy feed. Every count in the run summary
 * looks plausible. The only thing that catches it is an invariant that has to balance.
 *
 * <h2>The invariant</h2>
 *
 * <p>Every hop of every landed record ends in exactly one of three states: it produced a canonical
 * record, it was quarantined, or it was skipped because something it depended on failed. So for a
 * run over {@code n} landed records through a mapping of {@code h} hops:
 *
 * <pre>produced + quarantined + skipped = n × h</pre>
 *
 * <p>A non-zero residual is not a metric that has drifted. It is arithmetic that does not balance,
 * and it means the platform cannot say what happened to data an agency gave it.
 *
 * <p>Severity is {@link Severity#ERROR}. Like a projection divergence and unlike source drift, this
 * is not a signal to go and look — it is a known loss.
 *
 * @param context which run failed to balance
 * @param stage where the accounting was taken, e.g. {@code bronze-to-silver}
 * @param expected how many hop outcomes the landed records should have produced
 * @param accounted how many were actually accounted for, in any state
 * @param produced hop outcomes that yielded a canonical record
 * @param quarantined hop outcomes that failed a contract gate
 * @param skipped hop outcomes not attempted because a dependency failed
 */
public record CompletenessBreach(
        PipelineContext context,
        String stage,
        long expected,
        long accounted,
        long produced,
        long quarantined,
        long skipped) implements ObservabilityEvent {

    public CompletenessBreach {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(stage, "stage");
        if (expected == accounted) {
            // Constructing one of these when the books balance would put an ERROR in the stream
            // saying nothing is wrong, and an error nobody can act on is how a stream stops being
            // read. A balanced run emits nothing at all.
            throw new IllegalArgumentException(
                    "A completeness breach requires an imbalance; both sides were " + expected);
        }
    }

    /**
     * Signed residual. Negative means records went missing; positive means more came out than went
     * in, which is the duplication half of the same failure and just as wrong.
     */
    public long residual() {
        return accounted - expected;
    }

    /** Whether data was lost, as opposed to duplicated. The two need different investigations. */
    public boolean isLoss() {
        return residual() < 0;
    }

    @Override
    public EventType type() {
        return EventType.COMPLETENESS_BREACH;
    }

    @Override
    public Severity severity() {
        return Severity.ERROR;
    }

    @Override
    public Map<String, Object> attributes() {
        Map<String, Object> attributes = new LinkedHashMap<>(context.attributes());
        attributes.put("stage", stage);
        attributes.put("expected", expected);
        attributes.put("accounted", accounted);
        attributes.put("residual", residual());
        attributes.put("produced", produced);
        attributes.put("quarantined", quarantined);
        attributes.put("skipped", skipped);
        return attributes;
    }

    @Override
    public String summary() {
        return "%s does not balance: expected %d hop outcome(s), accounted for %d (%+d) -- "
                .formatted(stage, expected, accounted, residual())
                + "%d produced, %d quarantined, %d skipped"
                        .formatted(produced, quarantined, skipped);
    }
}
