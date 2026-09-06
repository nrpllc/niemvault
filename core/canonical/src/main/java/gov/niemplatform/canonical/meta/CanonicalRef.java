package gov.niemplatform.canonical.meta;

import java.io.Serializable;
import java.util.Objects;

/**
 * A typed reference to another canonical entity.
 *
 * <p>Carries the target type name alongside the identity so a reference is resolvable without
 * consulting the mapping that produced it -- which is what makes the graph projection able to
 * write an edge from the association alone.
 */
public record CanonicalRef(String typeName, CanonicalId id) implements Serializable {

    public CanonicalRef {
        Objects.requireNonNull(typeName, "typeName");
        Objects.requireNonNull(id, "id");
    }

    public static CanonicalRef to(String typeName, CanonicalId id) {
        return new CanonicalRef(typeName, id);
    }

    public static CanonicalRef to(String typeName, String id) {
        return new CanonicalRef(typeName, CanonicalId.of(id));
    }

    @Override
    public String toString() {
        return typeName + "/" + id.value();
    }
}
