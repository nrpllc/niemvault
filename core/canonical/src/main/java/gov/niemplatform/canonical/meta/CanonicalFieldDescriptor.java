package gov.niemplatform.canonical.meta;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * Metadata for one canonical field: its shape, its NIEM provenance, and its constraints.
 *
 * <p>This is what the contract layer (spec §4.2) derives its expectations from, so a field
 * cannot exist in the model without the validator knowing how to check it.
 *
 * @param name field name as it appears on the record
 * @param type value space of the field
 * @param required whether absence is a contract violation
 * @param repeated whether the value is a list of {@code type} rather than a single value
 * @param provenance NIEM origin, or {@code null} when this is an extension
 * @param extension justification, or {@code null} when NIEM-sourced
 * @param codeList permitted values for a {@link FieldType#CODE} field; empty otherwise
 * @param refType target canonical type name for a {@link FieldType#REF} field; {@code null} otherwise
 */
public record CanonicalFieldDescriptor(
        String name,
        FieldType type,
        boolean required,
        boolean repeated,
        NiemProvenance provenance,
        ExtensionJustification extension,
        List<String> codeList,
        String refType) implements Serializable {

    public CanonicalFieldDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        codeList = codeList == null ? List.of() : List.copyOf(codeList);
        if ((provenance == null) == (extension == null)) {
            throw new IllegalArgumentException(
                    "Field '" + name + "' must declare exactly one of NIEM provenance or an extension justification");
        }
    }

    /** Whether this field is a platform extension rather than NIEM-sourced. */
    public boolean isExtension() {
        return extension != null;
    }
}
