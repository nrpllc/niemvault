package gov.niemplatform.projections.api;

import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;

/**
 * Writes canonical silver into one shape of gold (spec §4.6).
 *
 * <p>Gold is polymorphic: the same canonical silver source projects into a graph, a search index,
 * and a warehouse, and each is a different answer to a different question. Nothing above this
 * interface may know which one it is talking to — spec §2 calls the graph writer swappable, and
 * that is only true if no backend type leaks past here.
 *
 * <p>Two obligations every implementation carries:
 *
 * <ul>
 *   <li><strong>{@link #apply} is idempotent.</strong> Replay re-applies the same changes by
 *       design (criterion 6), so a projection that double-counted would turn a correct replay into
 *       a wrong graph.
 *   <li><strong>{@link #rebuild} replaces rather than merges.</strong> Spec §4.6 requires full
 *       rebuild from silver; a rebuild that merged on top of what was already there would leave
 *       records silver no longer contains.
 * </ul>
 */
public interface ProjectionWriter extends AutoCloseable {

    /** Which shape of gold this writes. */
    ProjectionType type();

    /**
     * Applies an incremental change set.
     *
     * @throws ProjectionException if the projection could not be written. Unlike a contract
     *     violation, this is fatal to the run: gold that silently diverges from silver is the
     *     condition §4.7's {@code ProjectionDivergence} exists to detect, and pressing on would
     *     leave an investigator querying something wrong.
     */
    void apply(CanonicalChangeSet changes);

    /**
     * Rebuilds the projection from a complete snapshot of silver, discarding what was there.
     *
     * <p>The operation criterion 6 turns on, and the reason a projection may never accumulate
     * state it cannot reconstruct.
     */
    void rebuild(CanonicalSnapshot snapshot);

    /**
     * How many records of a canonical type the projection currently holds.
     *
     * <p>Not in the interface spec §4.6 sketches, and added deliberately: §4.7 requires a
     * {@code ProjectionDivergence} event comparing projection counts against what silver implies,
     * and that comparison cannot be made from outside without asking the projection.
     */
    long count(CanonicalTypeDescriptor descriptor);

    @Override
    void close();
}
