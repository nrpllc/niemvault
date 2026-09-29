package gov.niemplatform.projections.graph;

import gov.niemplatform.settings.SettingDescriptor;
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
    public String summary() {
        return "Neo4j graph projection: NIEM associations as edges (ADR 0006).";
    }

    /** What this reads from a definition's settings, key for key (ADR 0037). */
    @Override
    public java.util.List<SettingDescriptor> settings() {
        return java.util.List.of(
                SettingDescriptor.text("uri").label("Bolt URI").required()
                        .describe("bolt://host:port").build(),
                SettingDescriptor.text("user").label("User").defaultsTo("neo4j").build(),
                SettingDescriptor.text("passwordEnv").label("Password variable").envVarName().build(),
                SettingDescriptor.text("database").label("Database")
                        .describe("Empty uses the server's default database.").build());
    }

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
