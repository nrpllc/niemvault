package gov.niemplatform.canonical.meta;

import java.util.Optional;

/**
 * Looks up canonical types by name.
 *
 * <p>Exists so that consumers -- the contract loader, the mapping compiler, the catalogue -- can
 * resolve a canonical type without depending on any particular generated catalogue class. A
 * domain module ships its own generated catalogue, and a deployment may load several; this is
 * the seam that lets them be composed.
 */
@FunctionalInterface
public interface CanonicalTypeResolver {

    /** The canonical type with this simple name, e.g. {@code Person}. */
    Optional<CanonicalTypeDescriptor> byName(String name);

    /** A resolver over a fixed set of types, e.g. one module's generated catalogue. */
    static CanonicalTypeResolver of(Iterable<CanonicalTypeDescriptor> types) {
        java.util.Map<String, CanonicalTypeDescriptor> index = new java.util.LinkedHashMap<>();
        types.forEach(type -> index.put(type.name(), type));
        java.util.Map<String, CanonicalTypeDescriptor> frozen = java.util.Map.copyOf(index);
        return name -> Optional.ofNullable(frozen.get(name));
    }

    /** Combines resolvers, first match wins. Lets a deployment load several domain modules. */
    static CanonicalTypeResolver composite(CanonicalTypeResolver... resolvers) {
        CanonicalTypeResolver[] copy = resolvers.clone();
        return name -> {
            for (CanonicalTypeResolver resolver : copy) {
                Optional<CanonicalTypeDescriptor> found = resolver.byName(name);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        };
    }
}
