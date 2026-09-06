package gov.niemplatform.canonical.meta;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runtime metadata for a canonical type, generated from the DSL (spec §4.1).
 *
 * <p>Everything downstream reads the model through this rather than through reflection over the
 * generated record: the contract layer derives schemas from it, the graph projection derives
 * node labels and edge shapes from it, and the catalogue registers it as an asset.
 *
 * @param name simple type name, e.g. {@code Person}
 * @param namespace canonical namespace the type lives in
 * @param version content version of the type, moving independently of the platform (spec §7)
 * @param kind entity or association
 * @param provenance NIEM origin, or {@code null} when this type is an extension
 * @param extension justification, or {@code null} when NIEM-sourced
 * @param fields declared fields, in declaration order
 * @param roles declared association roles; empty for entities
 */
public record CanonicalTypeDescriptor(
        String name,
        String namespace,
        String version,
        CanonicalKind kind,
        NiemProvenance provenance,
        ExtensionJustification extension,
        List<CanonicalFieldDescriptor> fields,
        List<CanonicalRoleDescriptor> roles) implements Serializable {

    /** Name of the platform-assigned identity component present on every canonical record. */
    public static final String CANONICAL_ID_FIELD = "canonicalId";

    public CanonicalTypeDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(kind, "kind");
        fields = List.copyOf(fields);
        roles = List.copyOf(roles);
        if ((provenance == null) == (extension == null)) {
            throw new IllegalArgumentException(
                    "Type '" + name + "' must declare exactly one of NIEM provenance or an extension justification");
        }
    }

    /** Stable identifier carried on every record of this type: {@code namespace#Name}. */
    public String qualifiedName() {
        return namespace + "#" + name;
    }

    /** Whether this type is a platform extension rather than NIEM-derived. */
    public boolean isExtension() {
        return extension != null;
    }

    public Optional<CanonicalFieldDescriptor> field(String fieldName) {
        return fields.stream().filter(f -> f.name().equals(fieldName)).findFirst();
    }

    public Optional<CanonicalRoleDescriptor> role(String roleName) {
        return roles.stream().filter(r -> r.name().equals(roleName)).findFirst();
    }

    /**
     * Every member name a record of this type may carry: the canonical identity, then roles,
     * then fields. Anything else on a record is an unexpected member and a contract violation.
     */
    public List<String> memberNames() {
        List<String> names = new java.util.ArrayList<>();
        names.add(CANONICAL_ID_FIELD);
        roles.forEach(r -> names.add(r.name()));
        fields.forEach(f -> names.add(f.name()));
        return List.copyOf(names);
    }
}
