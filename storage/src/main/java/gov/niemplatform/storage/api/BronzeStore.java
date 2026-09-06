package gov.niemplatform.storage.api;

import java.util.List;
import java.util.stream.Stream;

/**
 * Append-only, immutable raw landing (spec §4.4).
 *
 * <p><strong>There is deliberately no update, delete, or truncate method.</strong> Spec §4.4 says
 * bronze is never mutated or deleted by platform logic, and the most reliable way to enforce that
 * is to leave the operations off the interface entirely -- a rule that cannot be expressed in
 * code gets broken eventually. Retention deletion, when it is needed, will be an operator action
 * with its own audit trail and its own interface, not a pipeline capability.
 *
 * <p>Silver and gold must be fully rebuildable from what is stored here. That is both a
 * correctness property and an audit requirement, and it is why the payload is kept as bytes
 * rather than as parsed fields.
 */
public interface BronzeStore extends AutoCloseable {

    /**
     * Lands a batch and returns its receipt.
     *
     * <p>Atomic: the batch becomes visible to {@link #batches} and {@link #read} only once fully
     * written. A crash part-way leaves an unreferenced data file, never a partially readable
     * batch.
     *
     * @throws BronzeStorageException if the batch could not be committed. Unlike a contract
     *     violation, a landing failure <em>is</em> fatal to the run -- a record the platform
     *     accepted but did not store is the one outcome bronze exists to make impossible.
     */
    BronzeBatchReceipt append(BronzeBatch batch);

    /** Receipts for every batch landed for a source, in landing order. */
    List<BronzeBatchReceipt> batches(String sourceId);

    /**
     * Reads landed envelopes back, in batch order and then source order.
     *
     * <p>The stream holds file handles and must be closed. Integrity is checked as records are
     * read: a payload whose stored hash no longer matches raises rather than being returned, so
     * corruption surfaces at read time instead of propagating into silver.
     */
    Stream<RawEnvelope> read(String sourceId, BronzeRange range);

    /** Sources that have landed at least one batch. */
    List<String> sources();

    @Override
    void close();
}
