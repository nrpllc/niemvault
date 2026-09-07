package gov.niemplatform.runtime.replay;

import gov.niemplatform.storage.api.SilverCommit;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a replay rebuilt.
 *
 * <p>Counts rather than records: a replay of a real bronze range does not fit in memory, and a
 * result that tried to hold it would put a ceiling on what could be replayed. Verification reads
 * silver and gold back afterwards, which is what an auditor would do anyway.
 *
 * @param request what was asked for
 * @param envelopesRead bronze records reprocessed
 * @param commits silver commits made, by canonical type
 * @param quarantined records that failed a contract during the replay
 * @param projectionsRebuilt projection types rebuilt from the resulting silver
 */
public record ReplayResult(
        ReplayRequest request,
        long envelopesRead,
        Map<String, SilverCommit> commits,
        long quarantined,
        List<String> projectionsRebuilt) {

    public ReplayResult {
        Objects.requireNonNull(request, "request");
        commits = Map.copyOf(commits);
        projectionsRebuilt = List.copyOf(projectionsRebuilt);
    }

    /** Canonical records written across every type. */
    public long recordsWritten() {
        return commits.values().stream().mapToLong(SilverCommit::recordCount).sum();
    }

    /**
     * Whether the replay reprocessed everything it read without quarantining anything.
     *
     * <p>A replay that quarantines is not a failure -- the same bad data was quarantined the first
     * time -- but it is worth reporting, because a replay quarantining records the original run
     * did not means the mapping version being replayed is not the one that produced the data.
     */
    public boolean clean() {
        return quarantined == 0;
    }
}
