package gov.niemplatform.canonical.meta;

import java.io.Serializable;
import java.util.Objects;

/**
 * Metadata for one participant in a canonical association.
 *
 * @param name role name, e.g. {@code subject}
 * @param targetType simple name of the canonical entity type filling the role
 * @param provenance NIEM origin, or {@code null} when this is an extension
 * @param extension justification, or {@code null} when NIEM-sourced
 */
public record CanonicalRoleDescriptor(
        String name,
        String targetType,
        NiemProvenance provenance,
        ExtensionJustification extension) implements Serializable {

    public CanonicalRoleDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(targetType, "targetType");
        if ((provenance == null) == (extension == null)) {
            throw new IllegalArgumentException(
                    "Role '" + name + "' must declare exactly one of NIEM provenance or an extension justification");
        }
    }

    public boolean isExtension() {
        return extension != null;
    }
}
