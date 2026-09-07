package gov.niemplatform.storage.api;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import java.util.List;
import java.util.stream.Stream;

/**
 * Canonical silver storage (spec §2, §4.4, ADR 0005).
 *
 * <p>Backed by Iceberg. Time travel is the reason spec §2 pinned a table format at all: it is what
 * lets a replay be <em>verified</em> against the silver it claims to reproduce, rather than
 * asserted to match.
 *
 * <h2>Why this has a delete and {@link BronzeStore} does not</h2>
 *
 * <p>The asymmetry is deliberate and load-bearing. Bronze is the record of what a source actually
 * sent, so nothing in the platform may remove it. Silver is <em>derived</em> — spec §4.4 requires
 * it to be fully rebuildable from bronze — so dropping it is a legitimate operation, and
 * acceptance criterion 6 depends on being able to: "deleting silver and gold and running replay
 * reproduces both exactly". A silver store without a drop could not be used to prove the property
 * it exists to guarantee.
 */
public interface CanonicalStore extends AutoCloseable {

    /**
     * Creates the table for a canonical type if it does not exist.
     *
     * <p>The schema is derived from the descriptor rather than declared separately, so a table
     * cannot drift from the model that writes to it.
     */
    void ensureTable(CanonicalTypeDescriptor descriptor);

    /**
     * Appends records, returning the commit that made them visible.
     *
     * <p>Atomic: either every record in the call is visible or none is. A partially visible commit
     * would make a replay comparison meaningless.
     */
    SilverCommit append(CanonicalTypeDescriptor descriptor, List<Record> records);

    /** Reads the current contents of a canonical type. The stream holds resources; close it. */
    Stream<Record> read(CanonicalTypeDescriptor descriptor);

    /**
     * Reads a canonical type as it stood at an earlier commit.
     *
     * <p>This is what makes lineage and replay checkable: silver before a run and silver after it
     * are both addressable, so what a run changed is a fact rather than an inference.
     */
    Stream<Record> readAsOf(CanonicalTypeDescriptor descriptor, long snapshotId);

    /** Commit history for a canonical type, oldest first. */
    List<SilverSnapshot> history(CanonicalTypeDescriptor descriptor);

    /** How many records a canonical type currently holds. */
    long count(CanonicalTypeDescriptor descriptor);

    /**
     * Removes a canonical type's table entirely.
     *
     * <p>Legitimate because silver is derived and rebuildable from bronze. Backs acceptance
     * criterion 6.
     */
    void drop(CanonicalTypeDescriptor descriptor);

    /** Canonical type names that currently have a table. */
    List<String> types();

    @Override
    void close();
}
