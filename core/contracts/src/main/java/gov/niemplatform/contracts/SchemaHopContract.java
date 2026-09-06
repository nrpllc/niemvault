package gov.niemplatform.contracts;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.observability.Direction;
import java.io.Serializable;
import java.util.Objects;

/**
 * A hop contract backed by two schemas.
 *
 * <p>The only implementation Phase 1 needs. It is deliberately not the interface: agencies with
 * expectations that a schema cannot express -- cross-field rules, referential checks against a
 * lookup -- implement {@link HopContract} directly rather than bending the schema language into
 * a rules engine.
 *
 * <p>Serializable because Flink ships contracts to operators as part of the job graph.
 */
public final class SchemaHopContract implements HopContract, Serializable {

    private static final long serialVersionUID = 1L;
    private static final SchemaValidator VALIDATOR = new SchemaValidator();

    private final ContractId id;
    private final String hopId;
    private final Schema expects;
    private final Schema emits;

    public SchemaHopContract(ContractId id, String hopId, Schema expects, Schema emits) {
        this.id = Objects.requireNonNull(id, "id");
        this.hopId = Objects.requireNonNull(hopId, "hopId");
        this.expects = Objects.requireNonNull(expects, "expects");
        this.emits = Objects.requireNonNull(emits, "emits");
    }

    @Override
    public ContractId id() {
        return id;
    }

    @Override
    public String hopId() {
        return hopId;
    }

    @Override
    public Schema expects() {
        return expects;
    }

    @Override
    public Schema emits() {
        return emits;
    }

    @Override
    public ValidationResult validate(Record record, Direction direction) {
        return VALIDATOR.validate(record, schemaFor(direction));
    }

    @Override
    public String toString() {
        return "SchemaHopContract[" + id + ", hop=" + hopId
                + ", expects=" + expects.id() + ", emits=" + emits.id() + "]";
    }
}
