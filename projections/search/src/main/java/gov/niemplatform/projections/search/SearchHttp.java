package gov.niemplatform.projections.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * The REST calls the search projection makes, over the JDK's own HTTP client.
 *
 * <p>No Elasticsearch client library (ADR 0036). The official client pins a server major and
 * brings its own JSON stack, and every jar it names has to be carried into an air-gapped mirror.
 * The projection uses six endpoints, all of them plain JSON over HTTP.
 */
final class SearchHttp implements AutoCloseable {

    /** A response, with the body parsed when there is one. */
    record Response(int status, JsonNode body) {

        boolean ok() {
            return status >= 200 && status < 300;
        }

        /**
         * What went wrong, as Elasticsearch describes it, with any echoed field value removed.
         *
         * <p>A mapping failure quotes the value it could not parse ("Preview of field's value"),
         * and a record value in an exception message reaches a log a record never would (ADR 0015).
         */
        String describe() {
            JsonNode error = body.path("error");
            if (error.isMissingNode() || error.isNull()) {
                return "HTTP " + status;
            }
            if (error.isTextual()) {
                return "HTTP " + status + ": " + redact(error.asText());
            }
            JsonNode cause = error.path("root_cause").path(0);
            JsonNode described = cause.isMissingNode() ? error : cause;
            return "HTTP %d: %s: %s".formatted(status, described.path("type").asText("error"),
                    redact(described.path("reason").asText("")));
        }
    }

    private final HttpClient client;
    private final URI base;
    private final String authorization;
    private final Duration timeout;
    private final ObjectMapper json;

    SearchHttp(URI base, String authorization, Duration timeout, ObjectMapper json) {
        String root = Objects.requireNonNull(base, "base").toString();
        this.base = URI.create(root.endsWith("/") ? root.substring(0, root.length() - 1) : root);
        this.authorization = authorization;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.json = Objects.requireNonNull(json, "json");
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    URI base() {
        return base;
    }

    Response send(String method, String path, JsonNode body) {
        String payload = null;
        if (body != null) {
            try {
                payload = json.writeValueAsString(body);
            } catch (IOException e) {
                throw new IllegalStateException("could not serialise a request body", e);
            }
        }
        return exchange(method, path, payload, "application/json");
    }

    /** A {@code _bulk} request: newline-delimited JSON, ending in a newline. */
    Response sendNdjson(String path, String ndjson) {
        return exchange("POST", path, ndjson, "application/x-ndjson");
    }

    private Response exchange(String method, String path, String payload, String contentType) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .method(method, payload == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(payload));
        if (payload != null) {
            request.header("Content-Type", contentType);
        }
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        try {
            HttpResponse<String> response =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            String text = response.body();
            JsonNode parsed = text == null || text.isBlank()
                    ? MissingNode.getInstance()
                    : json.readTree(text);
            return new Response(response.statusCode(), parsed);
        } catch (IOException e) {
            throw new IllegalStateException("%s %s failed: %s".formatted(method, path, e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("%s %s was interrupted".formatted(method, path), e);
        }
    }

    static String redact(String reason) {
        int preview = reason.indexOf("Preview of field's value");
        String kept = preview < 0 ? reason : reason.substring(0, preview).trim() + " [value withheld]";
        return kept.length() > 400 ? kept.substring(0, 400) + "..." : kept;
    }

    @Override
    public void close() {
        client.close();
    }
}
