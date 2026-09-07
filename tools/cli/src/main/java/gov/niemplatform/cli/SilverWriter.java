package gov.niemplatform.cli;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.storage.api.CanonicalStore;
import gov.niemplatform.storage.api.SilverCommit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Appends an ingest's canonical records to the silver store.
 *
 * <h2>Appends, where replay rewrites</h2>
 *
 * <p>The difference is the whole point. An ingest adds what has just arrived, so it appends. A
 * replay reproduces a bronze range under a pinned mapping, so it drops and rewrites — appending
 * there would double every record it reprocessed and make criterion 6's comparison meaningless.
 * Same store, opposite obligations, which is why they are separate classes rather than a flag.
 *
 * <h2>Written in batches, not all at once</h2>
 *
 * <p>A day's feed does not fit in a summary object. Records are buffered per canonical type and
 * flushed when a batch fills, so memory is bounded by the batch rather than by the size of the
 * ingest.
 */
final class SilverWriter implements AutoCloseable {

    /** Records buffered per type before a commit. Bounded memory, few enough commits to be cheap. */
    private static final int BATCH = 2_000;

    private final CanonicalStore store;
    private final Map<String, CanonicalTypeDescriptor> descriptors;
    private final Map<String, List<Record>> buffered = new LinkedHashMap<>();
    private final Map<String, Long> written = new LinkedHashMap<>();
    private final Set<String> tablesEnsured = new LinkedHashSet<>();

    /**
     * @param types the canonical types the mapping may produce, however the caller keys them.
     *     Re-indexed here by qualified name, because that is what a {@link Record} carries: a
     *     record says {@code .../core/1.0#Incident}, not {@code Incident}, and a map keyed by
     *     simple name silently fails to find it.
     */
    SilverWriter(CanonicalStore store, Map<String, CanonicalTypeDescriptor> types) {
        this.store = store;
        this.descriptors = new LinkedHashMap<>();
        types.values().forEach(descriptor -> {
            descriptors.put(descriptor.qualifiedName(), descriptor);
            descriptors.put(descriptor.name(), descriptor);
        });
    }

    void accept(Record record) {
        List<Record> batch =
                buffered.computeIfAbsent(record.typeName(), type -> new java.util.ArrayList<>());
        batch.add(record);
        if (batch.size() >= BATCH) {
            commit(record.typeName());
        }
    }

    void acceptAll(List<Record> records) {
        records.forEach(this::accept);
    }

    /** Commits everything still buffered. */
    void flush() {
        List.copyOf(buffered.keySet()).forEach(this::commit);
    }

    /** How many records reached each canonical table. */
    Map<String, Long> written() {
        return Map.copyOf(written);
    }

    private void commit(String typeName) {
        List<Record> batch = buffered.remove(typeName);
        if (batch == null || batch.isEmpty()) {
            return;
        }
        CanonicalTypeDescriptor descriptor = descriptors.get(typeName);
        if (descriptor == null) {
            throw new IllegalStateException(
                    "The pipeline produced records of type '" + typeName
                            + "', which the canonical model does not declare");
        }
        if (tablesEnsured.add(typeName)) {
            store.ensureTable(descriptor);
        }
        SilverCommit commit = store.append(descriptor, batch);
        written.merge(typeName, (long) commit.recordCount(), Long::sum);
    }

    @Override
    public void close() {
        flush();
    }
}
