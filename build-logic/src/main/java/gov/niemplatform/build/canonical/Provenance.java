package gov.niemplatform.build.canonical;

/**
 * NIEM origin of a canonical type, field, or association role.
 *
 * <p>{@code niemType} is set at type level; {@code niemElement} at field/role level.
 * Both carry the owning {@code niemNamespace} so a type's fields may cite elements from
 * a different NIEM namespace than the type itself (which is common, e.g. a justice type
 * carrying niem-core elements).
 */
public record Provenance(String niemNamespace, String niemType, String niemElement) {
    public String reference() {
        return niemType != null ? niemType : niemElement;
    }
}
