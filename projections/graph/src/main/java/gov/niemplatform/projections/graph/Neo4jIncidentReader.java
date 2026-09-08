package gov.niemplatform.projections.graph;

import gov.niemplatform.projections.api.ProjectionException;
import gov.niemplatform.projections.api.ProjectionException.Operation;
import gov.niemplatform.projections.api.ProjectionType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

/**
 * Reads incidents back out of the graph projection (§4.6).
 *
 * <p>The projection has been write-only: {@link Neo4jProjectionWriter} puts canonical silver into
 * Neo4j and nothing read it except a test counting nodes. An operator asking what actually landed,
 * or which people recur across incidents, had to open a Neo4j browser and write Cypher.
 *
 * <p><strong>Read-only, and structurally so.</strong> Every statement here is a {@code MATCH}; there
 * is no method that writes. Silver is the source of truth and the graph is a projection of it, so a
 * change made here would be erased by the next replay without anyone noticing — which is worse than
 * not being able to make it.
 *
 * <h2>Incidents, not cases</h2>
 *
 * <p>Named for what the canonical model actually holds. A case in law enforcement aggregates
 * incidents and carries a lifecycle and an assigned investigator; the model has {@code Incident} and
 * no {@code Case}, and calling this a case view would quietly promise the aggregation it does not
 * do. Adding a real {@code Case} is canonical-model work with its own NIEM provenance question.
 */
public final class Neo4jIncidentReader implements AutoCloseable {

    /**
     * How many other incidents one person may contribute before the rest are left unlisted.
     *
     * <p>A bound, not a page. A person on two hundred incidents is a data quality problem or a
     * resolution failure, and rendering all of them would bury the finding rather than show it.
     */
    private static final int CONNECTIONS_PER_PERSON = 25;

    // Derived through GraphNaming rather than written out, so the reader cannot drift from what the
    // writer created. Spelling "PERSON_INCIDENT_ASSOCIATION" here would work until the day the
    // convention changed, and then return nothing rather than fail.
    private static final String PERSON = GraphNaming.label("Person");
    private static final String INCIDENT = GraphNaming.label("Incident");
    private static final String INVOLVED_IN = GraphNaming.relationshipType("PersonIncidentAssociation");

    private final Driver driver;
    private final String database;

    public Neo4jIncidentReader(String uri, String username, String password) {
        this(uri, username, password, null);
    }

    public Neo4jIncidentReader(String uri, String username, String password, String database) {
        Objects.requireNonNull(uri, "uri");
        this.database = database;
        try {
            this.driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
            this.driver.verifyConnectivity();
        } catch (RuntimeException e) {
            throw new ProjectionException(Operation.CONNECT, ProjectionType.GRAPH, null,
                    "could not connect to " + uri, e);
        }
    }

    private Session session() {
        return database == null
                ? driver.session()
                : driver.session(SessionConfig.forDatabase(database));
    }

    /**
     * The incidents in the graph, most recently reported first.
     *
     * <p>Ordered on {@code reportedDateTime}, which the projection stores as a temporal property
     * rather than a string precisely so this orders by time instead of by how the date was
     * formatted.
     */
    public List<IncidentView.Summary> incidents(int limit) {
        String cypher = """
                MATCH (i:%s)
                OPTIONAL MATCH (p:%s)-[:%s]->(i)
                WITH i, count(p) AS people
                RETURN i, people
                ORDER BY i.reportedDateTime DESC, i.incidentNumber DESC
                LIMIT $limit
                """.formatted(INCIDENT, PERSON, INVOLVED_IN);
        return query(cypher, Values.parameters("limit", limit), record -> {
            Value incident = record.get("i");
            return new IncidentView.Summary(
                    text(incident, "canonicalId"),
                    text(incident, "incidentNumber"),
                    text(incident, "reportedDateTime"),
                    text(incident, "callTypeCode"),
                    record.get("people").asInt());
        });
    }

    /**
     * One incident by the agency's own number, with everyone on it and where they turn up elsewhere.
     *
     * <p>Keyed on {@code incidentNumber} rather than canonical identity because that is what an
     * operator has: it is on the CAD export, on the paperwork, and in the phone call asking about
     * it. The canonical identity is on the way out, not the way in.
     *
     * <p>Empty rather than an exception when nothing matches. An incident number that is not in the
     * graph is an ordinary answer to an ordinary question — it may not have landed yet, or the
     * caller may have mistyped it — and it is the caller's business how to report that.
     */
    public Optional<IncidentView> incident(String incidentNumber) {
        Objects.requireNonNull(incidentNumber, "incidentNumber");

        List<IncidentView.Incident> found = query(
                "MATCH (i:%s {incidentNumber: $number}) RETURN i".formatted(INCIDENT),
                Values.parameters("number", incidentNumber),
                record -> toIncident(record.get("i")));

        if (found.isEmpty()) {
            return Optional.empty();
        }
        IncidentView.Incident incident = found.getFirst();

        String involvedCypher = """
                MATCH (p:%s)-[a:%s]->(i:%s {incidentNumber: $number})
                RETURN p, a.involvementCode AS involvementCode
                ORDER BY p.surName, p.givenName
                """.formatted(PERSON, INVOLVED_IN, INCIDENT);
        List<IncidentView.Involvement> involvements = query(
                involvedCypher,
                Values.parameters("number", incidentNumber),
                record -> new IncidentView.Involvement(
                        toPerson(record.get("p")), record.get("involvementCode").asString(null)));

        // One traversal, not one per person. Asking per person would issue a query per row of the
        // view and make an incident with twenty people twenty round trips.
        String connectedCypher = """
                MATCH (p:%s)-[here:%s]->(i:%s {incidentNumber: $number})
                MATCH (p)-[there:%s]->(other:%s)
                WHERE other.incidentNumber <> $number
                WITH p, here, there, other
                ORDER BY other.reportedDateTime DESC
                WITH p, here, collect({there: there, other: other})[..$perPerson] AS others
                UNWIND others AS link
                RETURN p,
                       here.involvementCode AS involvementHere,
                       link.other AS other,
                       link.there.involvementCode AS involvementThere
                ORDER BY p.surName, p.givenName, link.other.reportedDateTime DESC
                """.formatted(PERSON, INVOLVED_IN, INCIDENT, INVOLVED_IN, INCIDENT);
        List<IncidentView.Connection> connections = query(
                connectedCypher,
                Values.parameters("number", incidentNumber, "perPerson", CONNECTIONS_PER_PERSON),
                record -> new IncidentView.Connection(
                        toPerson(record.get("p")),
                        record.get("involvementHere").asString(null),
                        toIncident(record.get("other")),
                        record.get("involvementThere").asString(null)));

        return Optional.of(new IncidentView(incident, involvements, connections));
    }

    /** How many incidents the graph holds, so a caller can say "none yet" rather than "not found". */
    public long incidentCount() {
        return query("MATCH (i:%s) RETURN count(i) AS total".formatted(INCIDENT), Values.parameters(),
                record -> record.get("total").asLong()).getFirst();
    }

    private <T> List<T> query(String cypher, Value parameters, java.util.function.Function<Record, T> map) {
        try (Session session = session()) {
            return session.executeRead(tx -> {
                List<T> results = new ArrayList<>();
                tx.run(cypher, parameters).forEachRemaining(record -> results.add(map.apply(record)));
                return results;
            });
        } catch (RuntimeException e) {
            // The Cypher is not in the message. It is this class's own text rather than anything a
            // caller supplied, and a failed read that echoes a statement containing an incident
            // number puts a case reference into every log that catches it.
            throw new ProjectionException(Operation.READ, ProjectionType.GRAPH, null,
                    "could not read the graph projection", e);
        }
    }

    /*
     * Named apart from the public incident(String) deliberately. As an overload, every caller of
     * the public method has to resolve this one's parameter type too -- so a module that depends on
     * the reader could not compile without the Neo4j driver on its own compile classpath, purely
     * because of a private helper it can never call.
     */
    private static IncidentView.Incident toIncident(Value node) {
        return new IncidentView.Incident(
                text(node, "canonicalId"),
                text(node, "incidentNumber"),
                text(node, "reportedDateTime"),
                text(node, "locationAddressText"),
                text(node, "callTypeCode"),
                text(node, "beat"));
    }

    private static IncidentView.Person toPerson(Value node) {
        return new IncidentView.Person(
                text(node, "canonicalId"),
                text(node, "surName"),
                text(node, "givenName"),
                text(node, "middleName"),
                text(node, "birthDate"));
    }

    /**
     * One property as text.
     *
     * <p>Temporal and numeric properties are stringified rather than refused. The projection stores
     * a zoned datetime because the driver rejects {@code Instant} outright and because an
     * investigator has to be able to range-query it; a view that renders it needs the text, and
     * asking each caller to know which properties are temporal would be a worse trade.
     */
    private static String text(Value node, String property) {
        Value value = node.get(property);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.type().name().equals("STRING") ? value.asString() : value.toString();
    }

    @Override
    public void close() {
        driver.close();
    }
}
