package gov.niemplatform.projections.ods;

import gov.niemplatform.settings.SettingDescriptor;
import gov.niemplatform.projections.api.ProjectionContext;
import gov.niemplatform.projections.api.ProjectionDefinition;
import gov.niemplatform.projections.api.ProjectionDefinitionException;
import gov.niemplatform.projections.api.ProjectionFactory;
import gov.niemplatform.projections.api.ProjectionType;
import gov.niemplatform.projections.api.ProjectionWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Opens the PostgreSQL ODS from a projection definition (ADR 0035).
 *
 * <pre>{@code
 * type: ods
 * settings:
 *   jdbcUrl: jdbc:postgresql://localhost:15432/niem
 *   user: niem
 *   passwordEnv: NIEM_ODS_PASSWORD
 *   schema: canonical          # optional
 * }</pre>
 *
 * <p>Strict on setting names, as the definition is on its own keys (ADR 0010). A misspelled
 * {@code passwordEnv} that was ignored would connect without a password -- which against a local
 * trust-authenticated database succeeds, and writes an agency's records wherever that turns out to
 * be.
 */
public final class PostgresOdsProjectionFactory implements ProjectionFactory {

    static final String DEFAULT_SCHEMA = "canonical";

    static final Set<String> SETTINGS = Set.of("jdbcUrl", "user", "passwordEnv", "schema");

    @Override
    public String summary() {
        return "PostgreSQL operational data store: canonical current state in SQL (ADR 0035).";
    }

    /** What this reads from a definition's settings, key for key (ADR 0037). */
    @Override
    public java.util.List<SettingDescriptor> settings() {
        return java.util.List.of(
                SettingDescriptor.text("jdbcUrl").label("JDBC URL").required()
                        .describe("jdbc:postgresql://host:port/database").build(),
                SettingDescriptor.text("user").label("User").build(),
                SettingDescriptor.text("passwordEnv").label("Password variable").envVarName()
                        .describe("Name of the environment variable holding the password.").build(),
                SettingDescriptor.text("schema").label("Schema").defaultsTo(DEFAULT_SCHEMA)
                        .describe("Where canonical tables live. Operational state goes elsewhere.").build());
    }

    @Override
    public ProjectionType type() {
        return ProjectionType.ODS;
    }

    @Override
    public ProjectionWriter open(ProjectionDefinition definition, ProjectionContext context) {
        List<String> problems = new ArrayList<>();
        definition.settings().keySet().stream()
                .filter(key -> !SETTINGS.contains(key))
                .sorted()
                .forEach(key -> problems.add("unrecognised setting '" + key + "'; the ods projection "
                        + "accepts " + SETTINGS.stream().sorted().toList()));

        String jdbcUrl = definition.setting("jdbcUrl").orElse(null);
        if (jdbcUrl == null) {
            problems.add("'jdbcUrl' is required, e.g. jdbc:postgresql://host:5432/niem");
        } else if (!jdbcUrl.startsWith("jdbc:postgresql:")) {
            problems.add("'jdbcUrl' must be a PostgreSQL JDBC URL (jdbc:postgresql:...); the ODS is "
                    + "PostgreSQL by decision, not by default (ADR 0035)");
        }

        String schema = definition.setting("schema").orElse(DEFAULT_SCHEMA);
        if (!schema.matches("[a-z_][a-z0-9_]*")) {
            problems.add("'schema' must be a lower-case SQL identifier, found '" + schema + "'");
        } else if (schema.equals(PostgresOdsProjectionWriter.META_SCHEMA)) {
            problems.add("'schema' cannot be '" + PostgresOdsProjectionWriter.META_SCHEMA
                    + "', which holds the tenant claim and the run ledger");
        }

        if (!problems.isEmpty()) {
            throw new ProjectionDefinitionException(null, problems.stream()
                    .map(problem -> "projection '" + definition.projectionName() + "': " + problem)
                    .toList());
        }

        String password = definition.secret("passwordEnv", context.environment()).orElse(null);
        return new PostgresOdsProjectionWriter(
                jdbcUrl, definition.setting("user").orElse(null), password, schema, context);
    }
}
