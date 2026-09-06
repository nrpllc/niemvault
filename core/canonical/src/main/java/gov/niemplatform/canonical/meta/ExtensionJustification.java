package gov.niemplatform.canonical.meta;

import java.io.Serializable;

/**
 * Why a canonical construct extends beyond NIEM rather than deriving from it.
 *
 * <p>Spec §4.1 requires a written justification for every extension, and the build refuses to
 * generate a type without one. Carrying it at runtime lets the catalogue (§4.8) answer
 * "why does this field exist?" without reading the DSL sources.
 */
public record ExtensionJustification(String text) implements Serializable {

    public ExtensionJustification {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("An extension requires a written justification");
        }
    }
}
