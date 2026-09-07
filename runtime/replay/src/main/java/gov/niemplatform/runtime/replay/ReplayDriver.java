package gov.niemplatform.runtime.replay;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.projections.api.CanonicalSnapshot;
import gov.niemplatform.projections.api.ProjectionWriter;
import gov.niemplatform.projections.api.TypedRecords;
import gov.niemplatform.runtime.engine.MappingDefinition;
import gov.niemplatform.runtime.engine.MappingPipeline;
import gov.niemplatform.storage.api.BronzeStore;
import gov.niemplatform.storage.api.CanonicalStore;
import gov.niemplatform.storage.api.RawEnvelope;
import gov.niemplatform.storage.api.SilverCommit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Rebuilds silver and gold from bronze (spec §5).
 *
 * <p>Replay is a first-class operation, not a recovery script. It is what makes the platform's
 * central claim checkable: bronze is the record of what a source sent, and everything downstream is
 * derived from it, so everything downstream can be thrown away and reconstructed. Acceptance
 * criterion 6 is exactly that — delete silver and gold, replay, and get both back.
 *
 * <h2>What makes it deterministic</h2>
 *
 * <p>Four things, each decided elsewhere and load-bearing here:
 *
 * <ul>
 *   <li>Bronze payloads are the bytes that arrived, so a replay reinterprets the original data
 *       rather than an earlier interpretation of it (§4.4).
 *   <li>Bronze read order is batch order then source order, which does not vary between runs.
 *   <li>Canonical identities are derived, never generated — incident numbers from their source key,
 *       clusters from the resolution key that seeded them (ADR 0014). A random identifier anywhere
 *       would make every replay differ invisibly.
 *   <li>The mapping version is pinned by the request, so a replay applies the mapping that produced
 *       the data rather than whatever is current.
 * </ul>
 *
 * <p>The transformation itself is the same {@link MappingPipeline} a normal run uses. A replay with
 * its own mapping code would be a second implementation of the thing being verified, and would
 * eventually disagree with the first.
 */
public final class ReplayDriver {

    private final BronzeStore bronze;
    private final CanonicalStore silver;
    private final List<ProjectionWriter> projections;

    /**
     * @param projections projections to rebuild from the resulting silver; empty to rebuild silver
     *     only, which is what a mapping correction usually wants before anything is published
     */
    public ReplayDriver(BronzeStore bronze, CanonicalStore silver, List<ProjectionWriter> projections) {
        this.bronze = Objects.requireNonNull(bronze, "bronze");
        this.silver = Objects.requireNonNull(silver, "silver");
        this.projections = List.copyOf(projections);
    }

    /**
     * Replays a bronze range under a pinned mapping version.
     *
     * <p>Silver for the affected canonical types is <strong>dropped and rewritten</strong>, not
     * appended to. A replay that appended would double every record it reprocessed, and the
     * comparison criterion 6 asks for would be meaningless.
     *
     * @throws IllegalArgumentException if the pipeline's mapping is not the version requested,
     *     because replaying under a different mapping than the one named is not a replay
     */
    public ReplayResult replay(ReplayRequest request, MappingPipeline pipeline) {
        MappingDefinition definition = pipeline.definition();
        if (!definition.name().equals(request.mappingName())
                || !definition.version().equals(request.mappingVersion())) {
            throw new IllegalArgumentException(
                    "Replay asked for %s but was given %s; a replay under a different mapping is "
                            .formatted(request.qualifiedMapping(), definition.qualifiedName())
                            + "not a replay of the original run");
        }

        Map<String, CanonicalTypeDescriptor> descriptors = descriptorsByQualifiedName(pipeline);
        Map<String, List<Record>> produced = new LinkedHashMap<>();
        long envelopesRead = 0;
        long quarantined = 0;

        try (Stream<RawEnvelope> envelopes = bronze.read(request.sourceId(), request.range())) {
            for (RawEnvelope envelope : (Iterable<RawEnvelope>) envelopes::iterator) {
                envelopesRead++;
                MappingPipeline.Outcome outcome = pipeline.process(envelope, request.runId());
                quarantined += outcome.quarantinedHops().size();
                outcome.canonicalRecords().forEach(record ->
                        produced.computeIfAbsent(record.typeName(), type -> new ArrayList<>()).add(record));
            }
        }

        Map<String, SilverCommit> commits = rewriteSilver(descriptors, produced);
        List<String> rebuilt = rebuildProjections(descriptors, produced);

        return new ReplayResult(request, envelopesRead, commits, quarantined, rebuilt);
    }

    /**
     * Drops and rewrites silver for every type the replay produced.
     *
     * <p>Dropping is legitimate here and nowhere near bronze: silver is derived, and
     * {@code CanonicalStore} carries a drop for precisely this reason while {@code BronzeStore}
     * does not.
     */
    private Map<String, SilverCommit> rewriteSilver(
            Map<String, CanonicalTypeDescriptor> descriptors, Map<String, List<Record>> produced) {

        Map<String, SilverCommit> commits = new LinkedHashMap<>();
        produced.forEach((typeName, records) -> {
            CanonicalTypeDescriptor descriptor = descriptors.get(typeName);
            if (descriptor == null) {
                throw new IllegalStateException(
                        "The pipeline produced records of type '" + typeName
                                + "', which its own mapping does not declare");
            }
            silver.drop(descriptor);
            silver.ensureTable(descriptor);
            commits.put(descriptor.name(), silver.append(descriptor, records));
        });
        return commits;
    }

    /**
     * Rebuilds every projection from the silver this replay just wrote.
     *
     * <p>{@code rebuild} rather than {@code apply}: spec §4.6 requires a rebuild to replace rather
     * than merge, and a replay that merged into an existing graph would leave whatever the previous
     * mapping version produced sitting alongside the corrected records.
     */
    private List<String> rebuildProjections(
            Map<String, CanonicalTypeDescriptor> descriptors, Map<String, List<Record>> produced) {

        if (projections.isEmpty()) {
            return List.of();
        }
        List<TypedRecords> contents = new ArrayList<>();
        produced.forEach((typeName, records) ->
                contents.add(new TypedRecords(descriptors.get(typeName), records)));

        CanonicalSnapshot snapshot = new CanonicalSnapshot(contents);
        List<String> rebuilt = new ArrayList<>(projections.size());
        for (ProjectionWriter projection : projections) {
            projection.rebuild(snapshot);
            rebuilt.add(projection.type().id());
        }
        return List.copyOf(rebuilt);
    }

    /**
     * Canonical descriptors the mapping can produce, keyed by the qualified name records carry.
     *
     * <p>Taken from the pipeline's own mapping rather than from a global registry: a replay must
     * write exactly the types the pinned mapping version declares, not whatever the deployment
     * happens to have loaded.
     */
    private static Map<String, CanonicalTypeDescriptor> descriptorsByQualifiedName(MappingPipeline pipeline) {
        Map<String, CanonicalTypeDescriptor> byQualifiedName = new LinkedHashMap<>();
        pipeline.canonicalTypes().values()
                .forEach(descriptor -> byQualifiedName.put(descriptor.qualifiedName(), descriptor));
        return byQualifiedName;
    }
}
