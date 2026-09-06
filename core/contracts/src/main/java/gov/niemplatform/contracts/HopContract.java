package gov.niemplatform.contracts;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.observability.Direction;

/**
 * What one hop in the mapping graph expects and what it emits (spec §4.2).
 *
 * <p>Validation runs on <strong>both</strong> the input and the output of every hop. That is not
 * belt-and-braces: the two directions catch different failures. An input violation means the
 * source changed underneath the mapping. An output violation means the mapping itself is wrong.
 * Running only one leaves half the failure surface unwatched, and the failure this platform
 * exists to catch is the quiet kind that no single check would notice.
 *
 * <p>A violation emits a structured event and routes the record to quarantine. It does not
 * silently drop the record, and it does not by default halt the pipeline -- one bad record from
 * a source must not stop the other thousand from landing.
 */
public interface HopContract {

    /** Name and version of this contract, as carried in lineage and violation events. */
    ContractId id();

    /** Identifier of the hop this contract governs. */
    String hopId();

    /** The shape this hop accepts. */
    Schema expects();

    /** The shape this hop produces. */
    Schema emits();

    /**
     * Validates a record against the schema for the given direction.
     *
     * @param record the record entering or leaving the hop
     * @param direction {@link Direction#INPUT} validates against {@link #expects()},
     *     {@link Direction#OUTPUT} against {@link #emits()}
     */
    ValidationResult validate(Record record, Direction direction);

    /** The schema governing one direction of this hop. */
    default Schema schemaFor(Direction direction) {
        return direction == Direction.INPUT ? expects() : emits();
    }
}
