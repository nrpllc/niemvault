package gov.niemplatform.build.canonical;

import java.nio.file.Path;
import java.util.List;

/**
 * A single canonical type as declared in the DSL. One YAML file declares exactly one type.
 *
 * <p>Spec §4.1: every canonical type declares its NIEM provenance or explicitly declares
 * itself an extension with a written justification. That is enforced here by making
 * {@code provenance} and {@code extension} mutually exclusive and jointly required.
 */
public record TypeDef(
        String name,
        Kind kind,
        String namespace,
        String version,
        String doc,
        Provenance provenance,
        Extension extension,
        List<FieldDef> fields,
        List<RoleDef> roles,
        Path sourceFile) {

    public enum Kind {
        /** A standalone canonical entity, e.g. Person. */
        ENTITY,
        /** A NIEM-style association linking two or more entities by role. */
        ASSOCIATION
    }

    public boolean isExtension() {
        return extension != null;
    }

    /** Fully qualified canonical identifier, used by the catalogue and contract layer. */
    public String qualifiedName() {
        return namespace + "#" + name;
    }
}
