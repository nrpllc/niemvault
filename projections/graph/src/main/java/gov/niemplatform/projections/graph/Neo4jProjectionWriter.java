package gov.niemplatform.projections.graph;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalFieldDescriptor;
import gov.niemplatform.canonical.meta.CanonicalId;
import gov.niemplatform.canonical.meta.CanonicalKind;
import gov.niemplatform.canonical.meta.CanonicalRef;
import gov.niemplatform.canonical.meta.CanonicalRoleDescriptor;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.projections.api.CanonicalChangeSet;
import gov.niemplatform.projections.api.CanonicalSnapshot;
import gov.niemplatform.projections.api.ProjectionException;
import gov.niemplatform.projections.api.ProjectionException.Operation;
import gov.niemplatform.projections.api.ProjectionType;
import gov.niemplatform.projections.api.ProjectionWriter;
import gov.niemplatform.projections.api.TypedRecords;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;

/**
 * Projects canonical silver into a property graph (spec §4.6).
 *
 * <h2>How the canonical model becomes a graph</h2>
 *
 * <p>Spec §4.6 is explicit that NIEM's association structures are already graph edges, and that
 * this projection honours the model rather than flattening it. So:
 *
 * <ul>
 *   <li>A canonical <strong>entity</strong> becomes a node, labelled with its type name, keyed by
 *       its platform-assigned canonical identity.
 *   <li>A canonical <strong>association with two roles</strong> becomes a relationship between the
 *       two role endpoints, carrying the association's own fields as relationship properties. This
 *       is the case the model was designed for, and turning it into an intermediate node would be
 *       exactly the flattening §4.6 rules out.
 *   <li>A canonical association with <strong>more than two roles</strong> becomes a node with a
 *       relationship to each endpoint. A property graph has no hyperedges, so an n-ary association
 *       has to be reified; doing it silently for the two-role case as well would make every simple
 *       traversal two hops instead of one.
 * </ul>
 *
 * <p>Every write is a {@code MERGE} on canonical identity, so applying the same change set twice
 * leaves the same graph. Replay depends on that (criterion 6).
 */
public final class Neo4jProjectionWriter implements ProjectionWriter {

    private static final String IDENTITY_PROPERTY = "canonicalId";

    private final Driver driver;
    private final String database;

    public Neo4jProjectionWriter(String uri, String username, String password) {
        this(uri, username, password, null);
    }

    public Neo4jProjectionWriter(String uri, String username, String password, String database) {
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

    @Override
    public ProjectionType type() {
        return ProjectionType.GRAPH;
    }

    private Session session() {
        return database == null
                ? driver.session()
                : driver.session(org.neo4j.driver.SessionConfig.forDatabase(database));
    }

    // --- apply -----------------------------------------------------------

    @Override
    public void apply(CanonicalChangeSet changes) {
        if (changes.isEmpty()) {
            return;
        }
        try (Session session = session()) {
            // Entities before associations, always. A relationship needs both endpoints to exist,
            // and MERGE-ing an endpoint from inside the association write would create a node with
            // no properties that a later entity write would have to repair.
            ordered(changes.changes()).forEach(typed -> write(session, typed));
        } catch (ProjectionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProjectionException(Operation.APPLY, ProjectionType.GRAPH, null,
                    "could not apply %d record(s)".formatted(changes.size()), e);
        }
    }

    private static List<TypedRecords> ordered(List<TypedRecords> groups) {
        List<TypedRecords> entities = new ArrayList<>();
        List<TypedRecords> associations = new ArrayList<>();
        for (TypedRecords typed : groups) {
            if (typed.descriptor().kind() == CanonicalKind.ASSOCIATION) {
                associations.add(typed);
            } else {
                entities.add(typed);
            }
        }
        entities.addAll(associations);
        return entities;
    }

    private void write(Session session, TypedRecords typed) {
        CanonicalTypeDescriptor descriptor = typed.descriptor();
        try {
            for (Record record : typed.records()) {
                if (descriptor.kind() == CanonicalKind.ASSOCIATION) {
                    writeAssociation(session, descriptor, record);
                } else {
                    writeEntity(session, descriptor, record);
                }
            }
        } catch (ProjectionException e) {
            // Already specific about what went wrong. Wrapping it again would replace a message
            // naming the actual problem with one that only says how many records were in the batch.
            throw e;
        } catch (RuntimeException e) {
            throw new ProjectionException(Operation.APPLY, ProjectionType.GRAPH, descriptor.name(),
                    "could not write %d record(s)".formatted(typed.size()), e);
        }
    }

    private void writeEntity(Session session, CanonicalTypeDescriptor descriptor, Record record) {
        String identity = identityOf(record, descriptor);
        Map<String, Object> properties = propertiesOf(descriptor, record);

        String cypher = """
                MERGE (n:%s {%s: $identity})
                SET n += $properties
                """.formatted(label(descriptor), IDENTITY_PROPERTY);

        session.executeWrite(tx -> tx.run(cypher,
                Values.parameters("identity", identity, "properties", properties)).consume());
    }

    private void writeAssociation(Session session, CanonicalTypeDescriptor descriptor, Record record) {
        if (descriptor.roles().size() == 2) {
            writeBinaryAssociation(session, descriptor, record);
        } else {
            writeReifiedAssociation(session, descriptor, record);
        }
    }

    /** Two roles: a relationship, which is what the canonical model already describes. */
    private void writeBinaryAssociation(
            Session session, CanonicalTypeDescriptor descriptor, Record record) {

        CanonicalRoleDescriptor fromRole = descriptor.roles().get(0);
        CanonicalRoleDescriptor toRole = descriptor.roles().get(1);
        CanonicalRef from = record.get(fromRole.name(), CanonicalRef.class);
        CanonicalRef to = record.get(toRole.name(), CanonicalRef.class);

        if (from == null || to == null) {
            throw new ProjectionException(Operation.APPLY, ProjectionType.GRAPH, descriptor.name(),
                    "an association is missing a role reference; the mapping should not have "
                            + "emitted it, and a half-connected edge is worse than none", null);
        }

        Map<String, Object> properties = propertiesOf(descriptor, record);
        properties.put(IDENTITY_PROPERTY, identityOf(record, descriptor));

        // MATCH rather than MERGE on the endpoints: they must already exist, because entities are
        // written first. Creating them here would produce a node with no properties whose absence
        // nothing would report.
        String cypher = """
                MATCH (from:%s {%s: $fromId})
                MATCH (to:%s {%s: $toId})
                MERGE (from)-[r:%s {%s: $identity}]->(to)
                SET r += $properties
                """.formatted(
                        from.typeName(), IDENTITY_PROPERTY,
                        to.typeName(), IDENTITY_PROPERTY,
                        relationshipType(descriptor), IDENTITY_PROPERTY);

        var summary = session.executeWrite(tx -> tx.run(cypher, Values.parameters(
                "fromId", from.id().value(),
                "toId", to.id().value(),
                "identity", identityOf(record, descriptor),
                "properties", properties)).consume());

        if (summary.counters().relationshipsCreated() == 0 && summary.counters().propertiesSet() == 0) {
            // Neither created nor updated means neither endpoint matched. Silence here would leave
            // an association in silver with no edge in the graph, which is exactly the divergence
            // §4.7 exists to catch -- better to fail the run than to produce a quietly wrong graph.
            throw new ProjectionException(Operation.APPLY, ProjectionType.GRAPH, descriptor.name(),
                    "neither endpoint of the association was found in the graph; entities must be "
                            + "projected before the associations that reference them", null);
        }
    }

    /**
     * More than two roles: a node with an edge per endpoint.
     *
     * <p>A property graph has no hyperedges, so an n-ary association must be reified. Not built
     * into the two-role path because doing so would make every simple traversal two hops.
     */
    private void writeReifiedAssociation(
            Session session, CanonicalTypeDescriptor descriptor, Record record) {

        String identity = identityOf(record, descriptor);
        Map<String, Object> properties = propertiesOf(descriptor, record);

        session.executeWrite(tx -> {
            tx.run("MERGE (a:%s {%s: $identity}) SET a += $properties"
                            .formatted(label(descriptor), IDENTITY_PROPERTY),
                    Values.parameters("identity", identity, "properties", properties)).consume();

            for (CanonicalRoleDescriptor role : descriptor.roles()) {
                CanonicalRef endpoint = record.get(role.name(), CanonicalRef.class);
                if (endpoint == null) {
                    continue;
                }
                tx.run("""
                        MATCH (a:%s {%s: $identity})
                        MATCH (e:%s {%s: $endpointId})
                        MERGE (a)-[:%s]->(e)
                        """.formatted(
                                label(descriptor), IDENTITY_PROPERTY,
                                endpoint.typeName(), IDENTITY_PROPERTY,
                                role.name().toUpperCase(Locale.ROOT)),
                        Values.parameters("identity", identity, "endpointId", endpoint.id().value()))
                        .consume();
            }
            return null;
        });
    }

    // --- rebuild ---------------------------------------------------------

    @Override
    public void rebuild(CanonicalSnapshot snapshot) {
        try (Session session = session()) {
            // Replace, not merge. A rebuild that merged on top of what was there would leave
            // records silver no longer contains, which is the failure full-rebuild exists to fix.
            for (TypedRecords typed : snapshot.contents()) {
                CanonicalTypeDescriptor descriptor = typed.descriptor();
                if (descriptor.kind() == CanonicalKind.ASSOCIATION && descriptor.roles().size() == 2) {
                    session.executeWrite(tx -> tx.run(
                            "MATCH ()-[r:%s]-() DELETE r".formatted(relationshipType(descriptor)))
                            .consume());
                } else {
                    session.executeWrite(tx -> tx.run(
                            "MATCH (n:%s) DETACH DELETE n".formatted(label(descriptor))).consume());
                }
            }
            ordered(snapshot.contents()).forEach(typed -> write(session, typed));
        } catch (ProjectionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProjectionException(Operation.REBUILD, ProjectionType.GRAPH, null,
                    "could not rebuild from a snapshot of %d record(s)".formatted(snapshot.size()), e);
        }
    }

    // --- counts ----------------------------------------------------------

    @Override
    public long count(CanonicalTypeDescriptor descriptor) {
        String cypher = descriptor.kind() == CanonicalKind.ASSOCIATION && descriptor.roles().size() == 2
                ? "MATCH ()-[r:%s]->() RETURN count(r) AS total".formatted(relationshipType(descriptor))
                : "MATCH (n:%s) RETURN count(n) AS total".formatted(label(descriptor));
        try (Session session = session()) {
            return session.executeRead(tx -> tx.run(cypher).single().get("total").asLong());
        } catch (RuntimeException e) {
            throw new ProjectionException(Operation.COUNT, ProjectionType.GRAPH, descriptor.name(),
                    "could not count", e);
        }
    }

    // --- mapping helpers -------------------------------------------------

    private static String identityOf(Record record, CanonicalTypeDescriptor descriptor) {
        CanonicalId identity = record.get(CanonicalTypeDescriptor.CANONICAL_ID_FIELD, CanonicalId.class);
        if (identity == null) {
            throw new ProjectionException(Operation.APPLY, ProjectionType.GRAPH, descriptor.name(),
                    "a canonical record reached the projection without an identity", null);
        }
        return identity.value();
    }

    // Naming lives in GraphNaming, shared with the reader. The writer creating
    // PERSON_INCIDENT_ASSOCIATION while a reader matches PersonIncidentAssociation produces no
    // error at all -- just an empty result indistinguishable from an incident with nobody on it.

    private static String label(CanonicalTypeDescriptor descriptor) {
        return GraphNaming.label(descriptor.name());
    }

    private static String relationshipType(CanonicalTypeDescriptor descriptor) {
        return GraphNaming.relationshipType(descriptor.name());
    }

    /**
     * Canonical fields as graph properties.
     *
     * <p>Roles are excluded: they are the edge, not a property on it. Absent values are omitted
     * rather than written as null, because a property set to null in Neo4j is a property removed,
     * and writing it would be a slower way of doing nothing.
     */
    private static Map<String, Object> propertiesOf(CanonicalTypeDescriptor descriptor, Record record) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (CanonicalFieldDescriptor field : descriptor.fields()) {
            Object value = record.raw(field.name());
            if (value != null) {
                properties.put(field.name(), toGraphValue(value));
            }
        }
        return properties;
    }

    private static Object toGraphValue(Object value) {
        // Neo4j's driver maps java.time types natively, so dates and instants stay temporal in the
        // graph rather than becoming strings an investigator cannot range-query.
        if (value instanceof CanonicalId identity) {
            return identity.value();
        }
        if (value instanceof CanonicalRef reference) {
            return reference.typeName() + "/" + reference.id().value();
        }
        if (value instanceof BigDecimal decimal) {
            // Neo4j has no decimal type. Doubles lose precision, so the string form is stored and
            // the canonical model remains the authority on the value.
            return decimal.toPlainString();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(Neo4jProjectionWriter::toGraphValue).toList();
        }
        if (value instanceof Instant instant) {
            // The driver has no mapping for Instant -- it rejects it outright. A zoned datetime
            // at UTC is the same moment, and keeps the property temporal so an investigator can
            // range-query it rather than string-match a timestamp.
            return instant.atOffset(java.time.ZoneOffset.UTC);
        }
        return value;
    }

    @Override
    public void close() {
        driver.close();
    }
}
