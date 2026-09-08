package gov.niemplatform.projections.graph;

import java.util.Locale;

/**
 * What a canonical type is called once it is in the graph.
 *
 * <p>Shared by the writer and the reader, because they have to agree exactly. The writer creates
 * {@code PERSON_INCIDENT_ASSOCIATION} and a reader matching {@code PersonIncidentAssociation} finds
 * nothing — no error, no warning, an empty result that looks exactly like an incident with nobody on
 * it. A convention held in two places is a convention until one of them is edited.
 */
final class GraphNaming {

    /** Node label: the canonical type name, which is already PascalCase by the DSL's rules. */
    static String label(String canonicalTypeName) {
        return canonicalTypeName;
    }

    /** Relationship type: the canonical association name in Neo4j's SCREAMING_SNAKE convention. */
    static String relationshipType(String canonicalTypeName) {
        return canonicalTypeName
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toUpperCase(Locale.ROOT);
    }

    private GraphNaming() {}
}
