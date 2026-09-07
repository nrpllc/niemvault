package gov.niemplatform.runtime.engine;

import java.io.Serializable;

/**
 * Builds a {@link MappingPipeline} inside a Flink operator.
 *
 * <p>A pipeline holds contracts, resolution providers, and sinks, not all of which are sensibly
 * serializable -- a resolution provider may hold a connection. So the factory is what Flink ships
 * to its operators, and the pipeline is constructed once per operator instance when it opens.
 *
 * <p>Implementations must produce an equivalent pipeline every time they are called. Two operator
 * instances running different mappings would break criterion 7 in a way no assertion downstream
 * would catch.
 */
@FunctionalInterface
public interface MappingPipelineFactory extends Serializable {

    MappingPipeline create();
}
