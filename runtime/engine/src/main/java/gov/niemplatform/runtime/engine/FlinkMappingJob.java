package gov.niemplatform.runtime.engine;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.storage.api.RawEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.CloseableIterator;
import org.apache.flink.util.Collector;

/**
 * Compiles a mapping into a Flink job graph and runs it (spec §5).
 *
 * <p>This class is the whole of the platform's Flink coupling. The transformation itself lives in
 * {@link MappingPipeline}, which knows nothing about Flink -- so batch and streaming are not two
 * implementations that must be kept in step, they are two schedulings of one object.
 *
 * <p>The only difference between the modalities is {@link StreamExecutionEnvironment#setRuntimeMode}.
 * The source, the operator, the pipeline, and the sink are constructed identically. That is the
 * architectural bet of §5, and acceptance criterion 7 is the test that it holds.
 *
 * <h2>Phase 1 boundary</h2>
 *
 * <p>The source is bounded in both modalities. Running a bounded source under
 * {@link RuntimeExecutionMode#STREAMING} genuinely exercises the streaming scheduler, the
 * streaming operator lifecycle, and the streaming serialisation path, which is what criterion 7
 * turns on. An unbounded source arrives with the Kafka connector in Phase 2; nothing in this
 * class has to change for it, because the mapping never sees the source.
 */
public final class FlinkMappingJob {

    private FlinkMappingJob() {}

    /**
     * Runs a mapping over a bounded set of envelopes and collects the canonical output.
     *
     * <p>Parallelism is fixed at one. Not for correctness -- the pipeline is per-record and would
     * be correct at any parallelism -- but so that a criterion 7 comparison is comparing the
     * mapping's behaviour rather than the scheduler's interleaving.
     */
    public static List<Record> run(
            MappingPipelineFactory factory,
            List<RawEnvelope> envelopes,
            ExecutionMode mode,
            String runId) throws Exception {

        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(mode, "mode");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment(1, configuration());
        env.setParallelism(1);
        env.setRuntimeMode(mode == ExecutionMode.BATCH
                ? RuntimeExecutionMode.BATCH
                : RuntimeExecutionMode.STREAMING);

        DataStream<Record> canonical = env
                .fromData(envelopes, JavaValueTypeInfo.forValue(RawEnvelope.class))
                .process(new MappingOperator(factory, runId))
                .returns(JavaValueTypeInfo.forValue(Record.class))
                .name("map-" + runId);

        List<Record> collected = new ArrayList<>();
        try (CloseableIterator<Record> results = canonical.executeAndCollect()) {
            results.forEachRemaining(collected::add);
        }
        return List.copyOf(collected);
    }

    /**
     * Embedded single-node defaults, for a small agency or a test.
     *
     * <p>Deliberately empty. A local mini cluster derives a consistent memory model from its own
     * defaults, and overriding one dimension of it -- network memory, say -- makes the model
     * inconsistent and the cluster refuse to start. A cluster deployment supplies its own
     * configuration, and nothing about the mapping changes either way.
     */
    private static Configuration configuration() {
        return new Configuration();
    }

    /**
     * The one operator in the graph: builds the pipeline on open, then maps envelope to records.
     *
     * <p>A {@code ProcessFunction} rather than a {@code flatMap} so that a future hop needing side
     * outputs -- quarantine as a stream rather than a sink call -- does not require reshaping the
     * graph.
     */
    private static final class MappingOperator extends ProcessFunction<RawEnvelope, Record> {

        private static final long serialVersionUID = 1L;

        private final MappingPipelineFactory factory;
        private final String runId;

        private transient MappingPipeline pipeline;

        MappingOperator(MappingPipelineFactory factory, String runId) {
            this.factory = factory;
            this.runId = runId;
        }

        @Override
        public void open(OpenContext openContext) {
            pipeline = factory.create();
        }

        @Override
        public void processElement(RawEnvelope envelope, Context context, Collector<Record> out) {
            // Quarantining and violation reporting happen inside the pipeline, which is why a
            // rejected record simply produces no output here rather than failing the job.
            pipeline.process(envelope, runId).canonicalRecords().forEach(out::collect);
        }
    }
}
