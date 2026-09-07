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

    /** Phase 3. Relational shape for BI, analytics, and ML feature engineering. */
    public static final ProjectionType WAREHOUSE = new ProjectionType("warehouse");

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
