package gov.niemplatform.canonical.meta;

import java.io.Serializable;
import java.util.Objects;

/**
 * NIEM origin of a canonical type, field, or association role (spec §4.1).
 *
 * <p>{@code niemType} is populated at type level, {@code niemElement} at member level. Both
 * carry the owning namespace, because a type routinely draws members from a NIEM namespace
 * other than its own.
 */
public record NiemProvenance(String niemNamespace, String niemType, String niemElement)
        implements Serializable {

    public NiemProvenance {
        Objects.requireNonNull(niemNamespace, "niemNamespace");
        if (niemType == null && niemElement == null) {
            throw new IllegalArgumentException(
                    "NIEM provenance must cite a type or an element: " + niemNamespace);
        }
    }

    /** The NIEM type or element this canonical construct derives from. */
    public String reference() {
        return niemType != null ? niemType : niemElement;
    }
}
