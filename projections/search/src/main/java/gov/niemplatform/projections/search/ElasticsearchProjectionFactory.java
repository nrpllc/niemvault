package gov.niemplatform.projections.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.niemplatform.projections.api.ProjectionContext;
import gov.niemplatform.projections.api.ProjectionDefinition;
import gov.niemplatform.projections.api.ProjectionDefinitionException;
import gov.niemplatform.projections.api.ProjectionFactory;
import gov.niemplatform.projections.api.ProjectionType;
import gov.niemplatform.projections.api.ProjectionWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Opens the search projection from a definition (ADR 0035, ADR 0036).
 *
 * <pre>{@code
 * projection: leon-search
 * version: "1.0.0"
 * type: search
 * settings:
 *   url: http://localhost:19200
 *   indexPrefix: niem          # optional, default niem
 *   refresh: wait_for          # optional: wait_for (default), true or false
 *   user: niem                 # optional; needs passwordEnv
 *   passwordEnv: NIEM_SEARCH_PASSWORD
 *   timeoutSeconds: "30"       # optional
 * }</pre>
 *
 * <p>{@code refresh} defaults to {@code wait_for}: a write returns once it is searchable. An ingest
 * that reported records projected which a search then could not find for a second would teach an
 * operator that the report is approximate. {@code false} is for a bulk load that does not care.
 */
public final class ElasticsearchProjectionFactory implements ProjectionFactory {

    private static final Set<String> REFRESH = Set.of("wait_for", "true", "false");

    @Override
    public ProjectionType type() {
        return ProjectionType.SEARCH;
    }

    @Override
    public ProjectionWriter open(ProjectionDefinition definition, ProjectionContext context) {
        List<String> problems = new ArrayList<>();
        String name = definition.projectionName();

        URI url = null;
        Optional<String> declaredUrl = definition.setting("url");
        if (declaredUrl.isEmpty()) {
            problems.add("projection '" + name + "' needs a 'url' setting, e.g. http://localhost:19200");
        } else {
            try {
                url = URI.create(declaredUrl.get());
                if (!"http".equals(url.getScheme()) && !"https".equals(url.getScheme())) {
                    problems.add("'url' must be http or https, found '" + url.getScheme() + "'");
                }
            } catch (IllegalArgumentException e) {
                problems.add("'url' is not a URL: " + e.getMessage());
            }
        }

        String prefix = definition.setting("indexPrefix").orElse("niem");
        if (!SearchNaming.PREFIX.matcher(prefix).matches()) {
            problems.add("'indexPrefix' must be lower-case letters, digits and '-', starting with a "
                    + "letter or digit; found '" + prefix + "'");
        }

        String refresh = definition.setting("refresh").orElse("wait_for");
        if (!REFRESH.contains(refresh)) {
            problems.add("'refresh' must be one of " + REFRESH.stream().sorted().toList()
                    + ", found '" + refresh + "'");
        }

        Duration timeout = Duration.ofSeconds(30);
        Optional<String> declaredTimeout = definition.setting("timeoutSeconds");
        if (declaredTimeout.isPresent()) {
            try {
                long seconds = Long.parseLong(declaredTimeout.get());
                if (seconds <= 0) {
                    throw new NumberFormatException();
                }
                timeout = Duration.ofSeconds(seconds);
            } catch (NumberFormatException e) {
                problems.add("'timeoutSeconds' must be a positive whole number, found '"
                        + declaredTimeout.get() + "'");
            }
        }

        Optional<String> user = definition.setting("user");
        boolean passwordNamed = definition.setting("passwordEnv").isPresent();
        if (user.isPresent() != passwordNamed) {
            problems.add("'user' and 'passwordEnv' go together: basic authentication needs both, and "
                    + "one without the other connects as nobody");
        }

        if (!problems.isEmpty()) {
            throw new ProjectionDefinitionException(null, problems);
        }

        String authorization = null;
        if (user.isPresent()) {
            String password = definition.secret("passwordEnv", context.environment()).orElseThrow();
            authorization = "Basic " + Base64.getEncoder().encodeToString(
                    (user.get() + ":" + password).getBytes(StandardCharsets.UTF_8));
        }

        ObjectMapper json = new ObjectMapper();
        return new ElasticsearchProjectionWriter(
                new SearchHttp(url, authorization, timeout, json),
                json, prefix, refresh, context, Clock.systemUTC());
    }
}
