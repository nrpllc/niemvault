package gov.niemplatform.runtime.engine;

import gov.niemplatform.runtime.transforms.TransformSpec;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One hop in the mapping graph: a set of steps producing one canonical type (spec §4.2, §5).
 *
 * <p>Hops form a DAG rather than a chain, because one source row routinely yields several
 * canonical entities. A CAD person row produces a {@code Person}, and the incident row produces
 * an {@code Incident}, and a third hop produces the association between them -- which needs the
 * canonical identities the first two assigned. {@link #dependsOn()} is what expresses that, and
 * it is why this is a graph and not a list.
 *
 * <p>A dependent hop reads an upstream hop's assigned identity as
 * {@code <hopId>.canonicalId}, so the association never has to re-derive an identity that
 * identity resolution already decided.
 *
 * @param hopId identifier, unique within the mapping and carried on every event and lineage record
 * @param contractName contract governing this hop, by name
 * @param contractVersion contract version, so a hop pins the contract it was written against
 * @param dependsOn hop identifiers whose output this hop reads; empty for a hop reading only the
 *     decoded source record
 * @param steps transformation steps, applied in order
 * @param identity how this hop's canonical identity is decided
 * @param roles for an association hop, role name to the hop supplying that role's entity
 * @param scratch step targets that are intermediates rather than output. A mapping routinely
 *     computes a value on the way to another -- a split name before it is upper-cased -- and
 *     those must not reach the output record, where the contract would rightly reject them as
 *     unexpected fields. Declared explicitly rather than inferred, so a typo in a step target
 *     is a load-time error instead of a field that silently disappears.
 */
public record HopDefinition(
        String hopId,
        String contractName,
        String contractVersion,
        List<String> dependsOn,
        List<TransformSpec> steps,
        IdentitySpec identity,
        Map<String, String> roles,
        List<String> scratch) implements Serializable {

    public HopDefinition {
        Objects.requireNonNull(hopId, "hopId");
        Objects.requireNonNull(contractName, "contractName");
        Objects.requireNonNull(contractVersion, "contractVersion");
        Objects.requireNonNull(identity, "identity");
        dependsOn = List.copyOf(dependsOn);
        steps = List.copyOf(steps);
        roles = Map.copyOf(roles);
        scratch = List.copyOf(scratch);

        if (hopId.isBlank()) {
            throw new IllegalArgumentException("A hop needs an identifier");
        }
        for (String role : roles.values()) {
            if (!dependsOn.contains(role)) {
                throw new IllegalArgumentException(
                        "Hop '%s' fills a role from hop '%s' but does not declare it in dependsOn"
                                .formatted(hopId, role));
            }
        }
    }

    /** A hop with no scratch fields, which is the common case. */
    public static HopDefinition of(
            String hopId,
            String contractName,
            String contractVersion,
            List<String> dependsOn,
            List<TransformSpec> steps,
            IdentitySpec identity,
            Map<String, String> roles) {
        return new HopDefinition(hopId, contractName, contractVersion, dependsOn, steps,
                identity, roles, List.of());
    }

    /** Whether this hop produces an association rather than a standalone entity. */
    public boolean isAssociation() {
        return !roles.isEmpty();
    }

    /** How a dependent hop refers to this hop's assigned identity. */
    public String identityReference() {
        return hopId + ".canonicalId";
    }
}
