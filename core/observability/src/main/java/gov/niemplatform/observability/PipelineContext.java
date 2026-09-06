package gov.niemplatform.observability;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Where in the platform an event came from.
 *
 * <p>Every event carries one. An event that cannot be attributed to a source, a run, and --
 * where applicable -- a hop and a mapping version is an event nobody can act on, which is the
 * failure mode of unstructured logging that spec §4.7 is reacting against.
 *
 * @param sourceId stable identifier of the configured source, e.g. {@code riverton-pd-cad}
 * @param runId identifier of the pipeline run that produced the event
 * @param hopId hop within the mapping DAG, or {@code null} outside a hop
 * @param mappingVersion content version of the mapping in force, or {@code null} where none applies
 */
public record PipelineContext(String sourceId, String runId, String hopId, String mappingVersion)
        implements Serializable {

    public PipelineContext {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(runId, "runId");
    }

    /** Context outside any particular hop, e.g. for landing or projection events. */
    public static PipelineContext of(String sourceId, String runId) {
        return new PipelineContext(sourceId, runId, null, null);
    }

    /** Context within a hop of a specific mapping version. */
    public static PipelineContext ofHop(String sourceId, String runId, String hopId, String mappingVersion) {
        return new PipelineContext(sourceId, runId, hopId, mappingVersion);
    }

    /**
     * Returns a copy located at a specific hop.
     *
     * <p>Lets a run-level context be created once and narrowed per hop, so a source identifier
     * cannot drift between the events emitted by different hops of the same run.
     */
    public PipelineContext withHop(String atHopId, String atMappingVersion) {
        return new PipelineContext(sourceId, runId, atHopId, atMappingVersion);
    }

    /** Flattened form for structured sinks. */
    public Map<String, Object> attributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("sourceId", sourceId);
        attributes.put("runId", runId);
        if (hopId != null) {
            attributes.put("hopId", hopId);
        }
        if (mappingVersion != null) {
            attributes.put("mappingVersion", mappingVersion);
        }
        return attributes;
    }
}
