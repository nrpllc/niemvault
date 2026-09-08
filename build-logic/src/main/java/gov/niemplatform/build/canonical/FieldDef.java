package gov.niemplatform.build.canonical;

import java.util.List;

/** A field on a canonical type. */
public record FieldDef(
        String name,
        String type,
        boolean required,
        boolean repeated,
        String doc,
        Provenance provenance,
        Extension extension,
        List<String> codeList,
        String refType) {

    public boolean isExtension() {
        return extension != null;
    }
}
