package gov.niemplatform.observability;

import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

/**
 * The books for a run: what landed, and what became of every hop of it.
 *
 * <p>Double-entry, deliberately. The pipeline already knows how many records it produced; counting
 * that alone tells you nothing, because the number it produced is exactly the number it produced.
 * The only useful question is whether that number, plus everything it declined to produce and why,
 * adds up to the work it was given. It has to balance against something.
 *
 * <p>Every hop of every landed record ends in exactly one of three states — produced, quarantined,
 * or skipped for a failed dependency — so:
 *
 * <pre>produced + quarantined + skipped = landed × hopsPerRecord</pre>
 *
 * <p>This also checks a property nothing else does: that a hop which neither failed nor was skipped
 * actually produced its canonical record. A hop returning quietly with nothing shows up here as a
 * residual and nowhere else at all.
 *
 * <h2>Counting hop outcomes, not records</h2>
 *
 * <p>A record is not the unit, because a record is not all-or-nothing here: one bad hop costs that
 * hop and its dependents, not the record. Counting records would make a partially mapped record
 * either a whole loss or a whole success, and it is neither.
 *
 * <p>Not thread-safe, and deliberately not synchronised. One account belongs to one run; making it
 * safe for concurrent use would invite it to be shared across parallel operators, where the counts
 * would be right and the conclusion still wrong — a distributed run's driver cannot see what
 * happened inside its operators, and an account that silently reported only the driver's share
 * would be worse than none.
 */
public final class RecordAccount implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String stage;
    private final int hopsPerRecord;

    private long landed;
    private long produced;
    private long quarantined;
    private long skipped;

    /**
     * @param stage where this accounting is taken, e.g. {@code bronze-to-silver}
     * @param hopsPerRecord how many hops the mapping runs for each landed record
     */
    public RecordAccount(String stage, int hopsPerRecord) {
        this.stage = Objects.requireNonNull(stage, "stage");
        if (hopsPerRecord < 1) {
            throw new IllegalArgumentException(
                    "A mapping runs at least one hop per record; got " + hopsPerRecord);
        }
        this.hopsPerRecord = hopsPerRecord;
    }

    /** One record came out of bronze and was handed to the pipeline. */
    public void landed() {
        landed++;
    }

    /**
     * What the pipeline did with one landed record.
     *
     * <p>Takes counts rather than the pipeline's own outcome type on purpose: this module is
     * depended on by contracts and by the engine above it, so it cannot depend on either. The three
     * numbers are the whole of what the accounting needs.
     */
    public void accountFor(int producedHops, int quarantinedHops, int skippedHops) {
        produced += producedHops;
        quarantined += quarantinedHops;
        skipped += skippedHops;
    }

    /** Hop outcomes the landed records should have produced. */
    public long expected() {
        return landed * hopsPerRecord;
    }

    /** Hop outcomes actually accounted for, in any state. */
    public long accounted() {
        return produced + quarantined + skipped;
    }

    /** Signed residual. Negative is loss, positive is duplication; both are wrong. */
    public long residual() {
        return accounted() - expected();
    }

    public boolean balances() {
        return residual() == 0;
    }

    public long landedCount() {
        return landed;
    }

    public long producedCount() {
        return produced;
    }

    public long quarantinedCount() {
        return quarantined;
    }

    public long skippedCount() {
        return skipped;
    }

    /**
     * The breach, if the books do not balance.
     *
     * <p>Empty when they do. A balanced run emits nothing rather than an event saying nothing is
     * wrong: an ERROR nobody can act on is how an event stream stops being read.
     */
    public Optional<CompletenessBreach> breach(PipelineContext context) {
        if (balances()) {
            return Optional.empty();
        }
        return Optional.of(new CompletenessBreach(
                context, stage, expected(), accounted(), produced, quarantined, skipped));
    }

    /** One line for a run summary. Counts only; an account never sees a record value. */
    public String summary() {
        return "%d landed x %d hop(s) = %d expected; %d accounted (%d produced, %d quarantined, "
                .formatted(landed, hopsPerRecord, expected(), accounted(), produced, quarantined)
                + "%d skipped)%s".formatted(skipped, balances() ? "" : " -- residual %+d".formatted(residual()));
    }

    @Override
    public String toString() {
        return "RecordAccount[stage=" + stage + ", " + summary() + "]";
    }
}
