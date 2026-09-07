package gov.niemplatform.runtime.engine;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How a hop decides the canonical identity of what it produces (spec §4.5).
 *
 * <p>Two modes, and the distinction is not cosmetic:
 *
 * <ul>
 *   <li>{@link Mode#RESOLVE} -- the entity is a real-world thing that several sources describe
 *       differently, so identity comes from resolution. People are the obvious case.
 *   <li>{@link Mode#DERIVE} -- the entity has an authoritative key in its source, so identity is a
 *       deterministic function of that key. An incident number is one; running it through entity
 *       resolution would add uncertainty where none exists.
 * </ul>
 *
 * <p>Both modes are deterministic given the same input and the same prior state, which is what
 * acceptance criteria 6 and 7 require. Nothing here may reach for a clock, a counter, or a UUID.
 *
 * @param mode which strategy applies
 * @param entityType canonical type being identified, e.g. {@code Person}
 * @param providerId resolution provider to use, for {@link Mode#RESOLVE}
 * @param attributes canonical field to resolver attribute name, for {@link Mode#RESOLVE}
 * @param deriveFrom emitted fields composing the key, in order, for {@link Mode#DERIVE}
 * @param prefix literal prefix on a derived identity, so identities are legible in the graph
 */
public record IdentitySpec(
        Mode mode,
        String entityType,
        String providerId,
        Map<String, String> attributes,
        List<String> deriveFrom,
        String prefix) implements Serializable {

    /** How identity is decided. */
    public enum Mode {
        /** Hand the attributes to a resolution provider and take the cluster it returns. */
        RESOLVE,
        /** Compose the identity deterministically from fields this hop emitted. */
        DERIVE
    }

    public IdentitySpec {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(entityType, "entityType");
        attributes = Map.copyOf(attributes);
        deriveFrom = List.copyOf(deriveFrom);

        if (mode == Mode.RESOLVE) {
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalArgumentException(
                        "Identity mode RESOLVE for '" + entityType + "' needs a provider");
            }
            if (attributes.isEmpty()) {
                throw new IllegalArgumentException(
                        "Identity mode RESOLVE for '" + entityType + "' needs at least one attribute");
            }
        } else if (deriveFrom.isEmpty()) {
            throw new IllegalArgumentException(
                    "Identity mode DERIVE for '" + entityType + "' needs at least one field to derive from");
        }
    }

    /** Identity assigned by a resolution provider. */
    public static IdentitySpec resolve(String entityType, String providerId, Map<String, String> attributes) {
        return new IdentitySpec(Mode.RESOLVE, entityType, providerId, attributes, List.of(), null);
    }

    /** Identity composed from the hop's own output. */
    public static IdentitySpec derive(String entityType, List<String> deriveFrom, String prefix) {
        return new IdentitySpec(Mode.DERIVE, entityType, null, Map.of(), deriveFrom, prefix);
    }
}
