package gov.niemplatform.disclosure;

import gov.niemplatform.canonical.meta.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * The append-only record of everything that crossed this agency's boundary (§4.8, ADR 0026).
 *
 * <h2>Append-only, like bronze, and for the same reason</h2>
 *
 * <p>There is no update and no delete on this interface. A disclosure log that can be edited is not
 * evidence, and the absence of those methods is what makes that structural rather than a matter of
 * who has permissions today.
 *
 * <h2>The record is written before the data moves</h2>
 *
 * <p>{@link #disclosing} exists so that ordering cannot be got wrong. It writes the record, and only
 * then produces what is released. A caller that released first and logged afterwards would, under
 * exactly the conditions an auditor cares about — a crash, a full disk, a network partition — produce
 * releases with no record of them. Those are the disclosures somebody will later need to explain.
 *
 * <p>The consequence is deliberate and worth stating: <strong>if the disclosure cannot be recorded,
 * the disclosure does not happen.</strong> An agency that cannot write to its own audit log has lost
 * the right to release data until it can, because it can no longer say what it released.
 */
public interface DisclosureLog {

    /** The agency whose disclosures this log holds. One log per agency, as one store per agency. */
    TenantId tenant();

    /**
     * Records a disclosure.
     *
     * @throws DisclosureLogException if it cannot be durably written
     */
    void append(DisclosureRecord record);

    /**
     * Records a disclosure and then produces what it releases.
     *
     * <p>The ordering is the contract. If {@code append} fails, the supplier is never invoked and
     * nothing crosses the boundary.
     *
     * @param release produces the payload to disclose; invoked only after the record is durable
     */
    default <T> T disclosing(DisclosureRecord record, Supplier<T> release) {
        append(record);
        return release.get();
    }

    /** Every disclosure in the window, oldest first. The query an auditor actually asks. */
    List<DisclosureRecord> between(Instant from, Instant to);

    /** Every disclosure involving one agency, in either direction. */
    List<DisclosureRecord> involving(TenantId other);
}
