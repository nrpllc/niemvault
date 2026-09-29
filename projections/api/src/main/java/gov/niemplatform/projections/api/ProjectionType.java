package gov.niemplatform.projections.api;

import java.io.Serializable;
import java.util.Objects;

/**
 * Identifies a kind of gold projection (spec §4.6).
 *
 * <p>A value type rather than an enum, for the same reason {@code ConnectorType} is: an agency may
 * ship a projection the platform has never heard of, and an enum would make the set closed.
 */
public record ProjectionType(String id) implements Serializable {

    /** The Phase 1 projection. NIEM's association structures are already graph edges. */
    public static final ProjectionType GRAPH = new ProjectionType("graph");

    /** Phase 2. What an investigator actually opens first. */
    public static final ProjectionType SEARCH = new ProjectionType("search");

    /**
     * The operational data store: current state, relational, queryable in SQL, and the store that
     * operational work on the data is recorded against (ADR 0035). Not {@link #WAREHOUSE} -- an
     * ODS is normalised current state that a workflow reads and writes beside, a warehouse is a
     * star schema for analytics, and the two answer different questions.
     */
    public static final ProjectionType ODS = new ProjectionType("ods");

    /** Phase 3. Relational shape for BI, analytics, and ML feature engineering. */
    public static final ProjectionType WAREHOUSE = new ProjectionType("warehouse");

    public static ProjectionType of(String id) {
        return new ProjectionType(id);
    }

    public ProjectionType {
        Objects.requireNonNull(id, "id");
        if (!id.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException(
                    "A projection type must be lower-case kebab-case, found '" + id + "'");
        }
    }

    @Override
    public String toString() {
        return id;
    }
}
