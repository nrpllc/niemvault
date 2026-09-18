package gov.niemplatform.cli;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalKind;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.projections.api.CanonicalChangeSet;
import gov.niemplatform.projections.api.ProjectionWriter;
import gov.niemplatform.projections.api.TypedRecords;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies an ingest's canonical records to gold, alongside the silver they are written to.
 *
 * <h2>Why an ingest projects at all</h2>
 *
 * <p>Until now only {@code replay} built projections, and that left a gap: the deployment runs
 * ingest as a CronJob and ships replay <em>suspended</em>, because replay drops and rewrites every
 * canonical table it produces and is not something an install performs. A projection that only a
 * suspended job could fill is a projection that is never filled.
 *
 * <p>Same records, opposite obligation from {@link SilverWriter}'s neighbour in replay. An ingest
 * adds what has just arrived, so it {@code apply}s an incremental change set; a replay reproduces
 * a bronze range and {@code rebuild}s from a snapshot. A projection's {@code apply} is idempotent
 * by contract, so re-landing the same drop leaves the same gold.
 *
 * <h2>Batched, and ordered within a batch</h2>
 *
 * <p>Records are buffered and flushed as whole change sets rather than one at a time, so memory is
 * bounded by the batch and not by the size of the ingest.
 *
 * <p>Entities are ordered ahead of associations in every change set, for the reason the graph
 * writer gives: an association needs both its endpoints to exist. Buffering each type separately
 * and letting them flush independently would eventually send an association in one batch and its
 * incident in the next, and a projection would be right to refuse it. So the buffer fills across
 * all types and drains as one ordered set.
 */
final class ProjectionFanout implements AutoCloseable {

    /** Records held before a change set is sent. Bounded memory, few enough round trips to be cheap. */
    private static final int BATCH = 2_000;

    private final List<ProjectionWriter> projections;
    private final Map<String, CanonicalTypeDescriptor> descriptors = new LinkedHashMap<>();
    private final Map<String, List<Record>> buffered = new LinkedHashMap<>();
    private final Map<String, Long> applied = new LinkedHashMap<>();
    private final String runId;

    private int held;

    /**
     * @param types the canonical types the mapping may produce, however the caller keys them.
     *     Re-indexed by qualified name as well as simple name, because a {@link Record} carries
     *     {@code .../core/1.0#Incident} and a map keyed by simple name silently fails to find it.
     */
    ProjectionFanout(
            List<ProjectionWriter> projections,
            Map<String, CanonicalTypeDescriptor> types,
            String runId) {
        this.projections = List.copyOf(projections);
        this.runId = runId;
        types.values().forEach(descriptor -> {
            descriptors.put(descriptor.qualifiedName(), descriptor);
            descriptors.put(descriptor.name(), descriptor);
        });
    }

    boolean active() {
        return !projections.isEmpty();
    }

    void acceptAll(List<Record> records) {
        if (projections.isEmpty()) {
            return;
        }
        for (Record record : records) {
            buffered.computeIfAbsent(record.typeName(), type -> new ArrayList<>()).add(record);
            held++;
        }
        if (held >= BATCH) {
            flush();
        }
    }

    /** Sends everything still buffered as one change set. */
    void flush() {
        if (projections.isEmpty() || held == 0) {
            return;
        }

        List<TypedRecords> entities = new ArrayList<>();
        List<TypedRecords> associations = new ArrayList<>();

        buffered.forEach((typeName, records) -> {
            CanonicalTypeDescriptor descriptor = descriptors.get(typeName);
            if (descriptor == null) {
                throw new IllegalStateException(
                        "The pipeline produced records of type '" + typeName
                                + "', which the canonical model does not declare");
            }
            TypedRecords typed = new TypedRecords(descriptor, records);
            if (descriptor.kind() == CanonicalKind.ASSOCIATION) {
                associations.add(typed);
            } else {
                entities.add(typed);
            }
            applied.merge(descriptor.name(), (long) records.size(), Long::sum);
        });

        List<TypedRecords> ordered = new ArrayList<>(entities);
        ordered.addAll(associations);

        CanonicalChangeSet changes = new CanonicalChangeSet(runId, ordered);
        // Sequentially, and a failure propagates. A projection that could not be written is fatal
        // to the run by contract: gold that silently diverges from silver is what §4.7 exists to
        // detect, and pressing on would leave an investigator querying something wrong.
        projections.forEach(projection -> projection.apply(changes));

        buffered.clear();
        held = 0;
    }

    /** How many records of each canonical type were applied. */
    Map<String, Long> applied() {
        return Map.copyOf(applied);
    }

    @Override
    public void close() {
        flush();
    }
}
