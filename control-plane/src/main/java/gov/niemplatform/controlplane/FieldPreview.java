package gov.niemplatform.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.niemplatform.canonical.core.CoreCanonicalTypes;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.canonical.meta.TenantId;
import gov.niemplatform.connectors.api.SourceDefinition;
import gov.niemplatform.contracts.HopContract;
import gov.niemplatform.contracts.QuarantineSink;
import gov.niemplatform.identity.api.InMemoryClusterIndex;
import gov.niemplatform.identity.api.IndexedResolutionProvider;
import gov.niemplatform.identity.internal.DeterministicResolutionProvider;
import gov.niemplatform.observability.ContractViolation;
import gov.niemplatform.observability.RecordingObservabilityEmitter;
import gov.niemplatform.runtime.engine.MappingDefinition;
import gov.niemplatform.runtime.engine.MappingPipeline;
import gov.niemplatform.storage.api.RawEnvelope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What each step of a mapping does to real records, for the author looking at that step.
 *
 * <p>The pipeline preview says how many records passed each stage. That answers "does it run", and
 * leaves the field editor -- where a step is actually written -- showing configuration with no data
 * in it: a {@code parseDate} and its pattern, and no way to see whether the pattern is right until a
 * run quarantines a feed. This runs the mapping <em>as it stands in the editor</em>, saved or not,
 * over a few records from its origin, and returns every step's inputs and output.
 *
 * <p>The same guarantees as the pipeline preview, because it reads through the same method: a
 * throwaway consumer group, nothing acknowledged, nothing written. The steps run through
 * {@link MappingPipeline#trace}, which is the engine's own evaluation rather than one written for
 * the editor.
 *
 * <p>Values are shown. These are the author's own records, returned to the author's browser, as the
 * pipeline preview's samples are; what ADR 0015 keeps values out of -- logs, events, a quarantine
 * reason -- still gets shapes only, and a violation here is reported exactly as the gate reports it.
 */
final class FieldPreview {

    private static final int VALUE_MAX = 160;

    private final PipelineDesigner designer;
    private final ObjectMapper json = new ObjectMapper();

    FieldPreview(PipelineDesigner designer) {
        this.designer = designer;
    }

    /**
     * @param request {@code yaml}: the mapping as the editor holds it; {@code hop}: the hop to
     *     trace; {@code origin}: the pipeline's origin draft, optional -- without one, the module's
     *     first source definition for the mapping's source is read; {@code limit}
     */
    ObjectNode preview(JsonNode request) {
        ObjectNode node = json.createObjectNode();
        node.put("writesNothing", true);
        MappingWorkspace workspace = designer.workspace();

        MappingWorkspace.ValidationReport report = workspace.validate(request.path("yaml").asText(""));
        MappingDefinition mapping = report.definition();
        if (mapping == null) {
            return refused(node, "The mapping does not load yet, so there is nothing to run: "
                    + String.join("; ", report.problems()));
        }
        String hopId = request.path("hop").asText(null);
        if (hopId == null || mapping.hops().stream().noneMatch(hop -> hop.hopId().equals(hopId))) {
            return refused(node, "Choose a hop of this mapping to preview.");
        }

        Map<String, HopContract> contracts = new LinkedHashMap<>();
        workspace.contracts().forEach(contract -> contracts.put(contract.hopId(), contract));
        List<String> uncontracted = mapping.hops().stream()
                .map(hop -> hop.hopId())
                .filter(id -> !contracts.containsKey(id))
                .toList();
        if (!uncontracted.isEmpty()) {
            return refused(node, "No contract gates " + String.join(", ", uncontracted)
                    + " yet, and a record is only ever mapped after its gate has checked it.");
        }

        Optional<SourceDefinition> origin = designer.originFor(request.get("origin"), mapping.sourceId(), node);
        if (origin.isEmpty()) {
            return refused(node, "No source definition in this module reads '" + mapping.sourceId()
                    + "', so there is nowhere to read sample records from.");
        }
        node.put("origin", origin.get().sourceId() + "/" + origin.get().connectorInstanceId());

        PipelineDesigner.Sample sample = designer.readSample(origin.get(), "field-preview",
                request.path("limit").asInt(8));
        if (sample.consumerGroup() != null) {
            node.put("consumerGroup", sample.consumerGroup());
        }
        if (!sample.read()) {
            return refused(node, sample.why());
        }

        Map<String, CanonicalTypeDescriptor> types = new LinkedHashMap<>();
        CoreCanonicalTypes.ALL.forEach(type -> types.put(type.name(), type));
        RecordingObservabilityEmitter recorder = new RecordingObservabilityEmitter();
        InMemoryClusterIndex index = new InMemoryClusterIndex(TenantId.of("designer-preview"));
        MappingPipeline pipeline = new MappingPipeline(mapping, contracts,
                Map.of(DeterministicResolutionProvider.PROVIDER_ID, new IndexedResolutionProvider(
                        new DeterministicResolutionProvider(index), index)),
                types, new QuarantineSink.InMemory(), recorder);

        var decoder = mapping.decoder().build();
        ArrayNode records = node.putArray("records");
        int admitted = 0;
        for (int i = 0; i < sample.envelopes().size(); i++) {
            RawEnvelope envelope = sample.envelopes().get(i);
            int before = recorder.eventsOfType(ContractViolation.class).size();
            MappingPipeline.HopTrace trace;
            try {
                trace = pipeline.trace(envelope, hopId, "designer-field-preview");
            } catch (RuntimeException e) {
                // A payload the decoder cannot split into the declared columns is the source's
                // shape disagreeing with the mapping's -- said per record, not as a failed request.
                ObjectNode entry = records.addObject();
                entry.put("record", i + 1);
                entry.put("raw", truncate(envelope.payloadAsText()));
                entry.put("admitted", false);
                entry.putArray("violations").add(String.valueOf(e.getMessage()));
                entry.putArray("steps");
                continue;
            }
            List<ContractViolation> violations = recorder.eventsOfType(ContractViolation.class)
                    .subList(before, recorder.eventsOfType(ContractViolation.class).size());
            ObjectNode entry = records.addObject();
            entry.put("record", i + 1);
            entry.put("raw", truncate(envelope.payloadAsText()));
            entry.put("admitted", trace.admitted());
            // Every column as decoded, whether or not a step reads it yet: a new mapping starts with
            // columns nothing reads, and those are exactly the ones an author needs to look at.
            ObjectNode columns = entry.putObject("columns");
            decoder.decode(envelope.payload()).values().forEach((name, value) -> columns.put(name, show(value)));
            if (trace.admitted()) {
                admitted++;
            }
            ArrayNode why = entry.putArray("violations");
            violations.forEach(violation -> why.add(violation.summary()));
            ArrayNode steps = entry.putArray("steps");
            for (MappingPipeline.StepTrace step : trace.steps()) {
                ObjectNode s = steps.addObject();
                s.put("index", step.index());
                s.put("target", step.target());
                s.put("type", step.type());
                ObjectNode inputs = s.putObject("inputs");
                step.inputs().forEach((name, value) -> inputs.put(name, show(value)));
                s.put("value", show(step.value()));
                if (step.failure() != null) {
                    s.put("failure", step.failure());
                }
            }
        }
        node.put("hop", hopId);
        node.put("read", sample.envelopes().size());
        node.put("admitted", admitted);
        node.put("ran", true);
        return node;
    }

    private ObjectNode refused(ObjectNode node, String why) {
        node.put("ran", false);
        node.put("why", why);
        return node;
    }

    private static String show(Object value) {
        return value == null ? null : truncate(String.valueOf(value));
    }

    private static String truncate(String value) {
        return value.length() <= VALUE_MAX ? value : value.substring(0, VALUE_MAX) + "…";
    }
}
