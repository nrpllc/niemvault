package gov.niemplatform.runtime.engine;

/**
 * Which modality a mapping runs under (spec §5).
 *
 * <p>The non-negotiable principle of §5 is one transformation definition, two execution
 * modalities. This enum is the <em>only</em> thing that differs between them: the same
 * {@link MappingPipeline} object is invoked either way, and the job graph is built by the same
 * code. If a second code path ever appears behind this switch, the architectural bet has been
 * lost -- which is what acceptance criterion 7 exists to detect.
 */
public enum ExecutionMode {
    /** Bounded input, processed as a batch. Flink treats it as a bounded stream. */
    BATCH,
    /** Processed as a stream, whether the input is bounded or not. */
    STREAMING
}
