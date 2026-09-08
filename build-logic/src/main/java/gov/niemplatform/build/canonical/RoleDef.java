package gov.niemplatform.build.canonical;

/**
 * A participant in a canonical association.
 *
 * <p>NIEM models associations as first-class types with named roles rather than as foreign
 * keys. The graph projection (§4.6) relies on that shape: a role becomes an edge endpoint,
 * not a flattened column.
 */
public record RoleDef(
        String name,
        String targetType,
        String doc,
        Provenance provenance,
        Extension extension) {

    public boolean isExtension() {
        return extension != null;
    }
}
