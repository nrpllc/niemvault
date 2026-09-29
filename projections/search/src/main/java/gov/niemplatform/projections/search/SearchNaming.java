package gov.niemplatform.projections.search;

import gov.niemplatform.canonical.meta.TenantId;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a canonical type is called once it is in the search cluster.
 *
 * <p>One place, for the reason {@code GraphNaming} is one place: the writer creating
 * {@code niem-x-person-incident-association} while a reader queries
 * {@code niem-x-personincidentassociation} produces no error, only an empty result that looks
 * exactly like an incident with nobody on it.
 *
 * <h2>Aliases, not indices</h2>
 *
 * <p>Every read and every incremental write goes through an alias named for the type. The index
 * behind it carries a timestamp and is replaced wholesale on rebuild, which is what lets a rebuild
 * load a complete new index while the old one keeps answering, and then swap in one atomic step.
 * A reader addressing a backing index by name would be left holding a deleted index after the
 * first rebuild.
 */
final class SearchNaming {

    /** Lower-case, starts alphanumeric: what Elasticsearch accepts at the front of an index name. */
    static final Pattern PREFIX = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    /** The canonical type's simple name, whether given simple or namespace-qualified. */
    static String simpleName(String typeName) {
        int hash = typeName.lastIndexOf('#');
        return hash < 0 ? typeName : typeName.substring(hash + 1);
    }

    /** {@code PersonIncidentAssociation} to {@code person-incident-association}. */
    static String kebab(String typeName) {
        return simpleName(typeName)
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .toLowerCase(Locale.ROOT);
    }

    /** The alias a type is read and written through. */
    static String alias(String prefix, TenantId tenant, String typeName) {
        return prefix + "-" + tenant.value() + "-" + kebab(typeName);
    }

    /** A backing index for an alias, unique to the moment it was created. */
    static String backingIndex(String alias, Instant createdAt) {
        return alias + "-" + stamp(createdAt);
    }

    static String stamp(Instant at) {
        return STAMP.format(at);
    }

    /** Where the cluster records which tenant it serves (ADR 0026). */
    static String claimIndex(String prefix) {
        return prefix + "-deployment";
    }

    private SearchNaming() {}
}
