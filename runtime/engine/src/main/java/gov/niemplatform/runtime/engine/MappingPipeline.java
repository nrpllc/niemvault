package gov.niemplatform.runtime.engine;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalId;
import gov.niemplatform.canonical.meta.CanonicalRef;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.contracts.ContractGate;
import gov.niemplatform.contracts.HopContract;
import gov.niemplatform.contracts.QuarantineSink;
import gov.niemplatform.contracts.QuarantinedRecord;
import gov.niemplatform.identity.api.ClusterId;
import gov.niemplatform.identity.api.EntityAttributes;
import gov.niemplatform.identity.api.ResolutionProvider;
import gov.niemplatform.identity.api.ResolutionResult;
import gov.niemplatform.observability.ContractViolation;
import gov.niemplatform.observability.Direction;
import gov.niemplatform.observability.ObservabilityEmitter;
import gov.niemplatform.observability.PipelineContext;
import gov.niemplatform.observability.ValueShape;
import gov.niemplatform.runtime.transforms.DelimitedRecordDecoder;
import gov.niemplatform.runtime.transforms.Transform;
import gov.niemplatform.runtime.transforms.TransformException;
import gov.niemplatform.runtime.transforms.TransformFactory;
import gov.niemplatform.runtime.transforms.TransformInput;
import gov.niemplatform.storage.api.RawEnvelope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Turns one landed envelope into canonical records (spec §5).
 *
 * <p><strong>Pure Java, by design.</strong> Nothing here knows about Flink. That is what makes
 * acceptance criterion 7 -- the identical mapping running unchanged in batch and streaming --
 * provable rather than asserted: both runtime modes invoke <em>this same object</em>, so there is
 * no second implementation that could drift. The Flink layer schedules; it does not transform.
 *
 * <p>Deterministic given the same input and the same resolver state. No clocks, no counters, no
 * randomness. Criterion 6 (replay reproduces silver exactly) rests on that as much as criterion 7
 * does.
 *
 * <h2>What happens to one envelope</h2>
 *
 * <ol>
 *   <li>The payload is decoded into a source-shaped record.
 *   <li>Each hop, in dependency order, validates its input, applies its steps, assigns a canonical
 *       identity, and validates its output. Both directions, every hop, per spec §4.2.
 *   <li>A hop that fails either gate quarantines the record and emits a violation. Its dependents
 *       are skipped, because an association to an entity that was never produced would be a
 *       dangling edge in the graph -- worse than a missing one.
 *   <li>Surviving hops contribute their canonical record to the output.
 * </ol>
 *
 * <p>One bad hop therefore costs that hop and what depends on it, not the record and not the run.
 */
public final class MappingPipeline {

    private final MappingDefinition definition;
    private final DelimitedRecordDecoder decoder;
    private final Map<String, HopContract> contractsByHop;
    private final Map<String, List<Transform>> stepsByHop;
    private final Map<String, ContractGate> inboundGates;
    private final Map<String, ContractGate> outboundGates;
    private final Map<String, ResolutionProvider> providers;
    private final Map<String, CanonicalTypeDescriptor> canonicalTypes;
    private final QuarantineSink quarantine;
    private final ObservabilityEmitter emitter;

    /**
     * @param contractsByHop contract for each hop id; every hop in the definition must have one
     * @param providers resolution providers by provider id, for hops resolving identity
     * @param canonicalTypes descriptors by canonical type name, for association role typing
     */
    public MappingPipeline(
            MappingDefinition definition,
            Map<String, HopContract> contractsByHop,
            Map<String, ResolutionProvider> providers,
            Map<String, CanonicalTypeDescriptor> canonicalTypes,
            QuarantineSink quarantine,
            ObservabilityEmitter emitter) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.contractsByHop = Map.copyOf(contractsByHop);
        this.providers = Map.copyOf(providers);
        this.canonicalTypes = Map.copyOf(canonicalTypes);
        this.quarantine = Objects.requireNonNull(quarantine, "quarantine");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.decoder = definition.decoder().build();

        Map<String, List<Transform>> steps = new LinkedHashMap<>();
        Map<String, ContractGate> inbound = new LinkedHashMap<>();
        Map<String, ContractGate> outbound = new LinkedHashMap<>();
        for (HopDefinition hop : definition.hops()) {
            HopContract contract = contractsByHop.get(hop.hopId());
            if (contract == null) {
                throw new IllegalArgumentException(
                        "Mapping '%s' hop '%s' has no contract".formatted(definition.qualifiedName(), hop.hopId()));
            }
            // Compiled once, here, rather than per record: a mapping is data, but compiling it on
            // every record would put regex compilation on the hot path.
            steps.put(hop.hopId(), hop.steps().stream().map(TransformFactory::create).toList());
            inbound.put(hop.hopId(), new ContractGate(contract, Direction.INPUT, quarantine, emitter));
            outbound.put(hop.hopId(), new ContractGate(contract, Direction.OUTPUT, quarantine, emitter));
        }
        this.stepsByHop = Map.copyOf(steps);
        this.inboundGates = Map.copyOf(inbound);
        this.outboundGates = Map.copyOf(outbound);
    }

    /** What one envelope produced. */
    public record Outcome(
            String envelopeId,
            List<Record> canonicalRecords,
            List<String> quarantinedHops,
            List<String> skippedHops) {

        public Outcome {
            canonicalRecords = List.copyOf(canonicalRecords);
            quarantinedHops = List.copyOf(quarantinedHops);
            skippedHops = List.copyOf(skippedHops);
        }

        public boolean fullyMapped() {
            return quarantinedHops.isEmpty() && skippedHops.isEmpty();
        }
    }

    public MappingDefinition definition() {
        return definition;
    }

    /**
     * Canonical types this pipeline can produce, by simple name.
     *
     * <p>Exposed for replay, which must write exactly the types the pinned mapping version declares
     * rather than whatever the deployment happens to have loaded.
     */
    public Map<String, CanonicalTypeDescriptor> canonicalTypes() {
        return canonicalTypes;
    }

    /** Maps one landed envelope. */
    public Outcome process(RawEnvelope envelope, String runId) {
        PipelineContext runContext = PipelineContext.of(definition.sourceId(), runId);
        Record decoded = decoder.decode(envelope.payload());

        List<Record> produced = new ArrayList<>();
        List<String> quarantined = new ArrayList<>();
        Set<String> failed = new LinkedHashSet<>();
        List<String> skipped = new ArrayList<>();
        Map<String, CanonicalId> identities = new LinkedHashMap<>();

        for (HopDefinition hop : definition.hopsInDependencyOrder()) {
            if (hop.dependsOn().stream().anyMatch(failed::contains)) {
                // An association to an entity that was never produced is a dangling edge. Skipping
                // is the lesser harm, and the skipped hop is reported rather than swallowed.
                failed.add(hop.hopId());
                skipped.add(hop.hopId());
                continue;
            }

            Optional<Record> validatedInput = inboundGates.get(hop.hopId()).check(decoded, runContext);
            if (validatedInput.isEmpty()) {
                failed.add(hop.hopId());
                quarantined.add(hop.hopId());
                continue;
            }

            Optional<Record> output = applyHop(hop, validatedInput.get(), identities, runContext, envelope);
            if (output.isEmpty()) {
                failed.add(hop.hopId());
                quarantined.add(hop.hopId());
                continue;
            }

            Optional<Record> validatedOutput = outboundGates.get(hop.hopId()).check(output.get(), runContext);
            if (validatedOutput.isEmpty()) {
                failed.add(hop.hopId());
                quarantined.add(hop.hopId());
                continue;
            }

            identities.put(hop.hopId(), validatedOutput.get().get("canonicalId", CanonicalId.class));
            produced.add(validatedOutput.get());
        }

        return new Outcome(envelope.envelopeId(), produced, quarantined, skipped);
    }

    /** Runs one hop's steps, assigns identity, and builds its canonical record. */
    private Optional<Record> applyHop(
            HopDefinition hop,
            Record input,
            Map<String, CanonicalId> identities,
            PipelineContext runContext,
            RawEnvelope envelope) {

        Map<String, Object> emitted = new LinkedHashMap<>();
        for (Transform step : stepsByHop.get(hop.hopId())) {
            try {
                emitted.put(step.target(), step.evaluate(new TransformInput(input, emitted)));
            } catch (TransformException e) {
                // A transform failure is bad data, not a platform fault: report it in the same
                // shape as a contract violation and quarantine, rather than failing the run.
                reportTransformFailure(hop, input, runContext, e);
                return Optional.empty();
            }
        }

        CanonicalId identity;
        try {
            identity = assignIdentity(hop, emitted, identities, envelope);
        } catch (IdentityUnavailableException e) {
            reportIdentityFailure(hop, input, runContext, e);
            return Optional.empty();
        }

        HopContract contract = contractsByHop.get(hop.hopId());
        Record.Builder builder = Record.builder(contract.emits().id());
        builder.set("canonicalId", identity);

        for (Map.Entry<String, String> role : hop.roles().entrySet()) {
            CanonicalId target = identities.get(role.getValue());
            if (target == null) {
                reportIdentityFailure(hop, input, runContext, new IdentityUnavailableException(
                        "role '%s' expects an identity from hop '%s', which produced none"
                                .formatted(role.getKey(), role.getValue())));
                return Optional.empty();
            }
            builder.set(role.getKey(), CanonicalRef.to(entityTypeOf(role.getValue()), target));
        }

        // Scratch targets are intermediates the mapping computed for its own use -- a split
        // name before it is upper-cased, say. They are not part of what the hop emits, and
        // letting them through would make every one an unexpected field at the output gate.
        Set<String> scratch = Set.copyOf(hop.scratch());
        emitted.forEach((field, value) -> {
            if (!scratch.contains(field)) {
                builder.set(field, value);
            }
        });
        return Optional.of(builder.build());
    }

    /** Entity type a hop produces, used to type association role references. */
    private String entityTypeOf(String hopId) {
        return definition.hops().stream()
                .filter(hop -> hop.hopId().equals(hopId))
                .map(hop -> hop.identity().entityType())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No hop named '" + hopId + "'"));
    }

    private CanonicalId assignIdentity(
            HopDefinition hop,
            Map<String, Object> emitted,
            Map<String, CanonicalId> identities,
            RawEnvelope envelope) {
        IdentitySpec spec = hop.identity();
        return switch (spec.mode()) {
            case RESOLVE -> resolveIdentity(hop, spec, emitted, envelope);
            case DERIVE -> deriveIdentity(spec, emitted, identities);
        };
    }

    private CanonicalId resolveIdentity(
            HopDefinition hop, IdentitySpec spec, Map<String, Object> emitted, RawEnvelope envelope) {
        ResolutionProvider provider = providers.get(spec.providerId());
        if (provider == null) {
            throw new IllegalStateException(
                    "Hop '%s' needs resolution provider '%s', which is not configured"
                            .formatted(hop.hopId(), spec.providerId()));
        }

        Map<String, String> attributes = new LinkedHashMap<>();
        spec.attributes().forEach((canonicalField, attributeName) -> {
            Object value = emitted.get(canonicalField);
            if (value != null) {
                attributes.put(attributeName, value.toString());
            }
        });
        if (attributes.isEmpty()) {
            throw new IdentityUnavailableException(
                    "no identity-bearing attribute was produced for entity type " + spec.entityType());
        }

        ResolutionResult result = provider.resolve(EntityAttributes.of(
                spec.entityType(), envelope.envelopeId(), attributes));
        // Spec §4.5: the platform keeps the cluster identifier, the confidence, and the evidence.
        // Nothing of the provider's internal state travels further than this line.
        return CanonicalId.of(result.clusterId().value());
    }

    /**
     * Composes an identity from this hop's own output, or from an upstream hop's identity.
     *
     * <p>An entry of the form {@code <hopId>.canonicalId} reads the identity an upstream hop was
     * assigned. That is how an association gets a stable identity: it is a function of the two
     * entities it links, so the same pair always produces the same association -- which is what
     * stops a replay from doubling every edge in the graph.
     */
    private CanonicalId deriveIdentity(
            IdentitySpec spec, Map<String, Object> emitted, Map<String, CanonicalId> identities) {
        List<String> parts = new ArrayList<>(spec.deriveFrom().size());
        for (String field : spec.deriveFrom()) {
            Object value;
            if (field.endsWith(".canonicalId")) {
                value = identities.get(field.substring(0, field.length() - ".canonicalId".length()));
            } else {
                value = emitted.get(field);
            }
            if (value == null || value.toString().isBlank()) {
                throw new IdentityUnavailableException(
                        "identity field '" + field + "' produced no value");
            }
            parts.add(value.toString());
        }
        String prefix = spec.prefix() == null ? "" : spec.prefix();
        return CanonicalId.of(prefix + String.join("/", parts));
    }

    private void reportTransformFailure(
            HopDefinition hop, Record input, PipelineContext runContext, TransformException failure) {
        emitFailure(hop, input, runContext, new ContractViolation.Failure(
                failure.target(), "transform:" + failure.transformType(),
                failure.expectation(), new ValueShape("String", null, failure.actualShape())));
    }

    private void reportIdentityFailure(
            HopDefinition hop, Record input, PipelineContext runContext, IdentityUnavailableException failure) {
        emitFailure(hop, input, runContext, new ContractViolation.Failure(
                "canonicalId", "identity", failure.getMessage(), ValueShape.absent()));
    }

    private void emitFailure(
            HopDefinition hop, Record input, PipelineContext runContext, ContractViolation.Failure failure) {
        HopContract contract = contractsByHop.get(hop.hopId());
        PipelineContext hopContext = runContext.withHop(hop.hopId(), contract.id().version());
        List<ContractViolation.Failure> failures = List.of(failure);

        String quarantineId = quarantine.quarantine(new QuarantinedRecord(
                contract.id(), hop.hopId(), Direction.OUTPUT, hopContext, input, failures));
        emitter.emit(new ContractViolation(
                hopContext, Direction.OUTPUT, contract.emits().id(), failures, quarantineId));
    }

    /** A hop could not establish an identity for what it produced. */
    private static final class IdentityUnavailableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        IdentityUnavailableException(String message) {
            super(message);
        }
    }
}
