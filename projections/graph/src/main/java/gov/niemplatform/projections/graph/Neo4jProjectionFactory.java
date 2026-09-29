package gov.niemplatform.projections.graph;

import gov.niemplatform.projections.api.ProjectionContext;
import gov.niemplatform.projections.api.ProjectionDefinition;
import gov.niemplatform.projections.api.ProjectionFactory;
import gov.niemplatform.projections.api.ProjectionType;
import gov.niemplatform.projections.api.ProjectionWriter;

/**
 * Opens the graph from a projection definition (ADR 0035).
 *
 * <pre>{@code
 * type: graph
 * settings:
 *   uri: bolt://localhost:17687
 *   user: neo4j
 *   passwordEnv: NIEM_NEO4J_PASSWORD
 *   database: neo4j        # optional; the server's default database when absent
 * }</pre>
 *
 * <p>Unlike {@code replay --neo4j-uri}, a graph opened this way is claimed for its tenant before
 * anything is written to it.
 */
public final class Neo4jProjectionFactory implements ProjectionFactory {

    @Override
    public ProjectionType type() {
        return ProjectionType.GRAPH;
    }

    @Override
    public ProjectionWriter open(ProjectionDefinition definition, ProjectionContext context) {
        String uri = definition.requiredSetting("uri");
        String user = definition.setting("user").orElse("neo4j");
        String password = definition.secret("passwordEnv", context.environment()).orElse("");
        String database = definition.setting("database").orElse(null);

        Neo4jProjectionWriter writer = new Neo4jProjectionWriter(uri, user, password, database);
        try {
            writer.claimFor(context.tenant());
            return writer;
        } catch (RuntimeException e) {
            writer.close();
            throw e;
        }
    }
}
