package gov.niemplatform.canonical.meta;

/** Whether a canonical type stands alone or links other types by role. */
public enum CanonicalKind {
    /** A standalone canonical entity, e.g. Person. */
    ENTITY,
    /**
     * A NIEM-style association linking two or more entities by named role.
     *
     * <p>Associations are first-class types, not foreign keys. The graph projection (spec §4.6)
     * depends on that: a role becomes an edge endpoint rather than a flattened column.
     */
    ASSOCIATION
}
