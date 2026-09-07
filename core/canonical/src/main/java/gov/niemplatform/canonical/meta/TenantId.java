package gov.niemplatform.canonical.meta;

import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The agency a record belongs to (ADR 0025).
 *
 * <h2>A tenant is an agency, not a deployment</h2>
 *
 * <p>One deployment may host a single large city or a dozen small agencies sharing the cost, and a
 * state and its counties are separate tenants at different levels of government. Nothing in the
 * platform may assume which shape it is running in, because for half the customers the assumption
 * would be wrong.
 *
 * <p>That is why this exists as a value rather than as configuration read once at start-up. A tenant
 * travels with the data. A deployment that hosts one tenant still names it: "the tenant is implied"
 * is how a shared deployment becomes commingled the first time somebody adds a second agency.
 *
 * <h2>It participates in identity</h2>
 *
 * <p>Cluster identifiers are seeded with it, so two agencies holding the same driver licence number
 * cannot silently resolve to one person. Under a shared deployment that would be commingling of
 * criminal justice records between agencies, arriving as a property of a hash function.
 *
 * <p>It also makes an identity mean something outside the tenant that minted it, which is what
 * federation needs. Linking a county's person to the state's is then a deliberate, attributable
 * assertion rather than a coincidence — see ADR 0025.
 */
public record TenantId(String value) implements Serializable, Comparable<TenantId> {

    /**
     * Lower-case, dot-separated segments: {@code co.riverton.pd}, {@code st.colorado}.
     *
     * <p>Constrained because a tenant appears in storage paths, table namespaces and identifiers.
     * A tenant named with a slash or a space would be a path traversal or a broken table name, and
     * discovering that at deploy time in an agency's environment is not the place.
     *
     * <p>Hierarchical by convention rather than by parsing. That {@code co.riverton.pd} sits under
     * Colorado is a fact about the naming an agency chose; the platform does not infer authority
     * from it, because a prefix is not a trust relationship.
     */
    private static final Pattern VALID = Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");

    /** Reserved for content that belongs to the platform rather than to any agency. */
    public static final TenantId PLATFORM = new TenantId("platform");

    public TenantId {
        Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "A tenant id is lower-case alphanumeric segments separated by '.' or '-', "
                            + "e.g. 'co.riverton.pd'. Got: '" + value + "'");
        }
    }

    public static TenantId of(String value) {
        Objects.requireNonNull(value, "value");
        return new TenantId(value.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * A path segment safe to use as a directory or table namespace.
     *
     * <p>The same as the value: the constraint above exists precisely so no escaping is needed, and
     * an escaping function is a place for two callers to disagree.
     */
    public String segment() {
        return value;
    }

    @Override
    public int compareTo(TenantId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
