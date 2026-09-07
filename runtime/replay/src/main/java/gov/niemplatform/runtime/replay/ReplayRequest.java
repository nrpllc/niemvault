package gov.niemplatform.runtime.replay;

import gov.niemplatform.storage.api.BronzeRange;
import java.util.Objects;

/**
 * What to replay (spec §5).
 *
 * <p>§5 defines replay precisely: "given a bronze range and a mapping version, rebuild silver
 * deterministically". Both halves are required here, and the mapping version is the half people
 * forget. Replaying under "the current mapping" would make a replay unrepeatable the moment a
 * mapping changed, which defeats the point of being able to reproduce a past run.
 *
 * @param sourceId the configured source to replay
 * @param range bronze batches to reprocess, inclusive
 * @param mappingName mapping to apply
 * @param mappingVersion the version of that mapping, pinned rather than resolved to latest
 * @param runId identifier carried on every event this replay emits
 */
public record ReplayRequest(
        String sourceId,
        BronzeRange range,
        String mappingName,
        String mappingVersion,
        String runId) {

    public ReplayRequest {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(mappingName, "mappingName");
        Objects.requireNonNull(mappingVersion, "mappingVersion");
        Objects.requireNonNull(runId, "runId");
    }

    /** Replays everything landed for a source under a pinned mapping version. */
    public static ReplayRequest all(String sourceId, String mappingName, String mappingVersion, String runId) {
        return new ReplayRequest(sourceId, BronzeRange.all(), mappingName, mappingVersion, runId);
    }

    /** The mapping this replay pins, as it appears in artifacts and events. */
    public String qualifiedMapping() {
        return mappingName + "@" + mappingVersion;
    }
}
