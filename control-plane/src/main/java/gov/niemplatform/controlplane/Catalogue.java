package gov.niemplatform.controlplane;

import gov.niemplatform.canonical.meta.CanonicalFieldDescriptor;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.connectors.api.ConnectorRegistry;
import gov.niemplatform.connectors.api.SourceConnector;
import gov.niemplatform.connectors.api.SourceDefinition;
import gov.niemplatform.contracts.HopContract;
import gov.niemplatform.contracts.SchemaHopContract;
import gov.niemplatform.runtime.engine.HopDefinition;
import gov.niemplatform.runtime.engine.MappingDefinition;
import gov.niemplatform.runtime.transforms.TransformSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a source sends, what the agency calls it, and what it becomes (§4.8, ADR 0019).
 *
 * <h2>Two halves, and only one of them is derivable</h2>
 *
 * <p>The structural half — sources, mappings, contracts, canonical types, their versions and their
 * NIEM provenance — is a reader over artifacts that already exist and describe themselves. Nothing
 * is captured for it; it is assembled.
 *
 * <p>The glossary half is not derivable from anything. NIEM provenance answers <em>what standard
 * does this canonical field come from</em>. A glossary answers a different question: <em>what does
 * this agency call it, and what did they mean</em>. That {@code BEAT} is a patrol district rather
 * than a postal boundary, and that {@code UNK} means "not recorded" rather than "unknown", exists
 * only in the head of whoever wrote the mapping until they write it down. It is what makes a mapping
 * reviewable by a records manager instead of only by an engineer.
 *
 * <p>So the glossary is read from {@code doc:} on declared columns and contract fields, and the
 * catalogue reports plainly where it is missing. A glossary that quietly omits undocumented terms
 * would suggest a source is better understood than it is.
 */
public final class Catalogue {

    /**
     * One term in a source's own vocabulary.
     *
     * @param term the name the source uses
     * @param meaning what the agency means by it, or empty when nobody has said
     * @param becomes the canonical constructs it feeds, in the order the mapping produces them
     * @param governed whether a contract checks it on the way in
     */
    public record Term(String term, Optional<String> meaning, List<String> becomes, boolean governed) {}

    /**
     * How a source reaches the platform, and what follows from that.
     *
     * <p>ADR 0027 requires a connector to <em>declare</em> its interaction mode and retention
     * posture rather than have them inferred from watching it behave. Declaring them is only half
     * useful if the only way to read them is to open the YAML: this is the other half, and the ADR
     * says so in as many words -- a non-retainable source "has to be visible in the catalogue".
     *
     * <p>Reported per definition, not per source, because a source may arrive by more than one
     * transport. Riverton CAD arrives as a nightly file drop and as a live topic, under one mapping;
     * that is the separation spec 4.3 draws, and flattening the two into a single "transport" field
     * would hide it.
     *
     * @param connectorType the transport, as the definition names it
     * @param connectorInstanceId which instance, so two of a kind stay distinguishable
     * @param interactionMode push, poll, or query -- how records arrive
     * @param retention whether records from this source may be kept
     * @param replayable whether bronze will hold what arrived, and so whether replay is available
     * @param freshnessSla how stale this source may get before it is a PipelineLag, if it says
     * @param problem why this definition could not be described, when it could not
     */
    public record Arrival(
            String connectorType,
            String connectorInstanceId,
            String interactionMode,
            String retention,
            boolean replayable,
            Optional<String> freshnessSla,
            Optional<String> problem) {

        /** Whether this arrival could be described at all. */
        public boolean described() {
            return problem.isEmpty();
        }
    }

    /** A canonical type the module produces, and where its shape comes from. */
    public record Produced(
            String name,
            String version,
            String provenance,
            List<String> fields,
            String byHop,
            String identity) {}

    /** One source, everything it sends, and everything that happens to it. */
    public record Source(
            String sourceId,
            String mappingName,
            String mappingVersion,
            String recordType,
            List<Term> vocabulary,
            List<Produced> produces,
            List<String> contracts,
            List<Arrival> arrivals) {

        /** Terms nobody has explained. The number that says how much of this is really catalogued. */
        public List<String> undocumented() {
            return vocabulary.stream()
                    .filter(term -> term.meaning().isEmpty())
                    .map(Term::term)
                    .toList();
        }

        /**
         * Whether anything says how this source actually arrives.
         *
         * <p>A gap of the same kind as an undocumented term: the catalogue can describe what the
         * source means and not how it gets here, which leaves retention -- a legal question, not an
         * architectural one -- unanswered for anybody who did not write the pipeline.
         */
        public boolean arrivalUndeclared() {
            return arrivals.isEmpty();
        }

        /** Terms the mapping never reads. Often a source sending more than anyone asked for. */
        public List<String> unused() {
            return vocabulary.stream()
                    .filter(term -> term.becomes().isEmpty())
                    .map(Term::term)
                    .toList();
        }
    }

    private Catalogue() {}

    /**
     * Builds the catalogue entry for one mapping and the contracts that gate it.
     *
     * @param canonicalTypes the canonical model this platform build carries, for provenance
     */
    public static Source of(MappingDefinition mapping, Map<String, HopContract> contractsByHop,
            List<CanonicalTypeDescriptor> canonicalTypes) {

        return of(mapping, contractsByHop, canonicalTypes, List.of(), ConnectorRegistry.of());
    }

    /**
     * Builds the catalogue entry, including how the source arrives.
     *
     * @param definitions every source definition in scope; those naming this source are described
     * @param registry the transports this deployment can actually read
     */
    public static Source of(MappingDefinition mapping, Map<String, HopContract> contractsByHop,
            List<CanonicalTypeDescriptor> canonicalTypes, List<SourceDefinition> definitions,
            ConnectorRegistry registry) {

        return new Source(
                mapping.sourceId(),
                mapping.name(),
                mapping.version(),
                mapping.decoder().emitsType(),
                vocabulary(mapping, contractsByHop),
                produced(mapping, canonicalTypes),
                contractsByHop.values().stream()
                        .map(contract -> contract.id().toString())
                        .sorted()
                        .toList(),
                arrivals(SourceDefinition.forSource(definitions, mapping.sourceId()), registry));
    }

    /**
     * Describes each way the source arrives, asking the connector rather than guessing.
     *
     * <p>Interaction mode and retention are the connector's answers, not the definition's: retention
     * in particular is configuration for one transport and a constant for another, and only the
     * connector knows which. That is why the definition is configured here -- which is safe, because
     * configuring validates settings and never touches the source. Reaching it is health(), and a
     * catalogue has no business doing that.
     *
     * <p>A definition this deployment cannot describe is listed with its reason rather than dropped.
     * A source silently missing from the catalogue is indistinguishable from a source nobody
     * configured, and those need different fixes.
     */
    private static List<Arrival> arrivals(
            List<SourceDefinition> definitions, ConnectorRegistry registry) {

        List<Arrival> arrivals = new ArrayList<>();
        for (SourceDefinition definition : definitions) {
            Optional<SourceConnector> connector = registry.forType(definition.type());
            if (connector.isEmpty()) {
                arrivals.add(undescribed(definition,
                        "no connector for transport " + definition.type() + " on the classpath"));
                continue;
            }
            try {
                SourceConnector configured = connector.get();
                configured.configure(definition.toConnectorConfig());
                arrivals.add(new Arrival(
                        definition.type().id(),
                        definition.connectorInstanceId(),
                        configured.interactionMode().name(),
                        configured.retention().name(),
                        configured.retention().landsInBronze(),
                        definition.declaredFreshnessSla().map(Object::toString),
                        Optional.empty()));
            } catch (RuntimeException notConfigurable) {
                arrivals.add(undescribed(definition, notConfigurable.getMessage()));
            }
        }
        return List.copyOf(arrivals);
    }

    private static Arrival undescribed(SourceDefinition definition, String problem) {
        return new Arrival(
                definition.type().id(),
                definition.connectorInstanceId(),
                "unknown",
                "undeclared",
                false,
                definition.declaredFreshnessSla().map(Object::toString),
                Optional.of(problem));
    }

    /**
     * Every column the source declares, what it means, and what it becomes.
     *
     * <p>The "becomes" side is derived rather than declared, and derived through the same
     * single-assignment reasoning {@link FieldGraph} uses: a column feeds a step, whose target feeds
     * another, and what an author wants to know is where the chain <em>ends</em>. {@code NAME_FULL}
     * becoming {@code surName}, {@code givenName} and {@code middleName} is the interesting fact;
     * that it passes through two splits on the way is not.
     */
    private static List<Term> vocabulary(MappingDefinition mapping,
            Map<String, HopContract> contractsByHop) {

        List<String> columns = mapping.decoder().columns();
        Map<String, Set<String>> reaches = new LinkedHashMap<>();
        columns.forEach(column -> reaches.put(column, new LinkedHashSet<>()));

        for (HopDefinition hop : mapping.hops()) {
            Set<String> scratch = Set.copyOf(hop.scratch());
            // Which columns each value in this hop ultimately came from.
            Map<String, Set<String>> origins = new LinkedHashMap<>();

            for (TransformSpec step : hop.steps()) {
                Set<String> from = new LinkedHashSet<>();
                for (String input : step.from()) {
                    if (columns.contains(input) && !origins.containsKey(input)) {
                        from.add(input);
                    } else {
                        from.addAll(origins.getOrDefault(input, Set.of()));
                    }
                }
                origins.put(step.target(), from);

                if (!scratch.contains(step.target())) {
                    String becomes = hop.identity().entityType() + "." + step.target();
                    from.forEach(column -> reaches.get(column).add(becomes));
                }
            }
        }

        boolean governed = !contractsByHop.isEmpty();
        List<Term> terms = new ArrayList<>();
        for (String column : columns) {
            terms.add(new Term(
                    column,
                    meaningOf(column, mapping, contractsByHop),
                    List.copyOf(reaches.get(column)),
                    governed && expectedBySomeContract(column, contractsByHop)));
        }
        return List.copyOf(terms);
    }

    /**
     * What the agency means by a column.
     *
     * <p>The mapping's own declaration wins over the contract's. Both are written by the same people
     * about the same field, and the mapping is where an author is thinking about meaning; a contract
     * is where they are thinking about shape.
     */
    private static Optional<String> meaningOf(String column, MappingDefinition mapping,
            Map<String, HopContract> contractsByHop) {

        Optional<String> declared = mapping.decoder().docFor(column);
        if (declared.isPresent()) {
            return declared;
        }
        return contractsByHop.values().stream()
                .filter(SchemaHopContract.class::isInstance)
                .map(SchemaHopContract.class::cast)
                .map(contract -> contract.expects().docFor(column))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static boolean expectedBySomeContract(String column,
            Map<String, HopContract> contractsByHop) {
        return contractsByHop.values().stream()
                .filter(SchemaHopContract.class::isInstance)
                .map(SchemaHopContract.class::cast)
                .anyMatch(contract -> contract.expects().field(column).isPresent());
    }

    /** The canonical types this mapping emits, with the provenance the model carries for them. */
    private static List<Produced> produced(MappingDefinition mapping,
            List<CanonicalTypeDescriptor> canonicalTypes) {

        List<Produced> produced = new ArrayList<>();
        for (HopDefinition hop : mapping.hopsInDependencyOrder()) {
            String typeName = hop.identity().entityType();
            Optional<CanonicalTypeDescriptor> descriptor = canonicalTypes.stream()
                    .filter(type -> type.name().equals(typeName))
                    .findFirst();

            produced.add(new Produced(
                    typeName,
                    descriptor.map(CanonicalTypeDescriptor::version).orElse("unknown"),
                    descriptor.map(Catalogue::provenanceOf).orElse("not in this platform's model"),
                    descriptor.map(type -> type.fields().stream()
                                    .map(CanonicalFieldDescriptor::name).toList())
                            .orElse(List.of()),
                    hop.hopId(),
                    hop.identity().mode() == gov.niemplatform.runtime.engine.IdentitySpec.Mode.RESOLVE
                            ? "resolved by " + hop.identity().providerId()
                            : "derived from " + String.join(" + ", hop.identity().deriveFrom())));
        }
        return List.copyOf(produced);
    }

    /**
     * Where a canonical type's shape comes from.
     *
     * <p>An extension says so, and says why. A type that deviates from the standard without
     * announcing it is the thing the provenance rule exists to prevent, and a catalogue that
     * rendered both the same way would undo that at the last step.
     */
    private static String provenanceOf(CanonicalTypeDescriptor type) {
        if (type.provenance() != null) {
            return type.provenance().niemNamespace() + " " + type.provenance().niemType();
        }
        return type.extension() == null
                ? "undeclared"
                : "platform extension — " + type.extension().text();
    }
}
