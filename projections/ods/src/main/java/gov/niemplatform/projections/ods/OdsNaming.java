package gov.niemplatform.projections.ods;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * What a canonical type or member is called once it is in the ODS.
 *
 * <p>Shared by the writer and its tests, and by anything that later queries the ODS, for the reason
 * {@code GraphNaming} exists: a writer creating {@code person_incident_association} and a reader
 * selecting from {@code personincidentassociation} gets "relation does not exist" at best, and at
 * worst -- once somebody has created the second table by hand -- an empty result that looks like an
 * incident with nobody on it.
 *
 * <p>snake_case, because PostgreSQL folds unquoted identifiers to lower case. A table called
 * {@code "PersonIncidentAssociation"} would work only for an analyst who remembered to quote it
 * every time, which is no analyst.
 */
public final class OdsNaming {

    /** PostgreSQL's identifier limit, in bytes. Longer names are silently truncated by the server. */
    static final int MAX_IDENTIFIER_BYTES = 63;

    /** Table for a canonical type: {@code PersonIncidentAssociation} to {@code person_incident_association}. */
    public static String table(String canonicalTypeName) {
        return snake(canonicalTypeName);
    }

    /** Column for a canonical field: {@code reportedDateTime} to {@code reported_date_time}. */
    public static String column(String canonicalFieldName) {
        return snake(canonicalFieldName);
    }

    /** Column holding the identity a role points at: {@code person} to {@code person_id}. */
    public static String roleColumn(String roleName) {
        return snake(roleName) + "_id";
    }

    /**
     * A constraint or index name, truncated here rather than by the server.
     *
     * <p>The server truncates silently too, but then a lookup by the untruncated name finds nothing,
     * the writer concludes the constraint is missing, and tries to create it again on every open.
     */
    static String objectName(String prefix, String table, String member) {
        String name = prefix + "_" + table + "_" + member;
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        return bytes.length <= MAX_IDENTIFIER_BYTES
                ? name
                : new String(bytes, 0, MAX_IDENTIFIER_BYTES, StandardCharsets.UTF_8);
    }

    /** Double-quoted, so a name that collides with a keyword ({@code order}, {@code user}) still works. */
    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String snake(String name) {
        return name
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .toLowerCase(Locale.ROOT);
    }

    private OdsNaming() {}
}
