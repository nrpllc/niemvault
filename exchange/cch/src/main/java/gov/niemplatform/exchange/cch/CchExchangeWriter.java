package gov.niemplatform.exchange.cch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.niemplatform.exchange.api.AssembledDocument;
import gov.niemplatform.exchange.api.ExchangeDefinition;
import gov.niemplatform.exchange.api.ExchangeDefinitionException;
import gov.niemplatform.exchange.api.ExchangeHealth;
import gov.niemplatform.exchange.api.ExchangeType;
import gov.niemplatform.exchange.api.ExchangeWriter;
import gov.niemplatform.exchange.api.SubmissionReceipt;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Submits assembled documents to a criminal history repository over HTTP (ADR 0034).
 *
 * <p>Replaces a writer that named three canonical types in constants and listed twelve fields one
 * Java line at a time. This one names none: what it carries is whatever the exchange definition's
 * {@code assemble} block gathered, and adding a charge to a submission is a change to that file.
 *
 * <h2>What is still hard-coded here, and why that is the right line</h2>
 *
 * <p>The transport is. This class knows it speaks HTTP, posts JSON, and reads a particular response
 * shape for acknowledgements. That is the same boundary spec §4.3 draws for a connector: transport
 * is boilerplate belonging to the adapter, and what moves across it is configuration. A repository
 * that spoke NIEM XML over a message queue would be a different {@link ExchangeType} in a different
 * jar, discovered through the registry, with the same assembly configuration serving both.
 *
 * <h2>Receipts, not a return code</h2>
 *
 * <p>One receipt per document. A repository accepts and rejects individually, and one malformed
 * charge does not invalidate the other forty arrests in the same send -- reporting the batch as
 * failed would have an operator resubmit records that were already posted.
 *
 * <p>An accepted submission may still be {@code PENDING}: a fingerprint-backed entry is answered
 * asynchronously, sometimes days later, against the identifier the repository issues here. That
 * identifier is carried on the receipt for exactly that reason. A writer that reported PENDING as
 * success would be claiming a criminal history entry that may yet be refused.
 *
 * <h2>Settings</h2>
 *
 * <table>
 *   <caption>Exchange settings</caption>
 *   <tr><th>Key</th><th>Meaning</th></tr>
 *   <tr><td>{@code endpoint}</td><td>Required. Where documents are posted.</td></tr>
 *   <tr><td>{@code tokenEnv}</td><td>Environment variable holding the bearer token. Never the token itself.</td></tr>
 *   <tr><td>{@code timeoutSeconds}</td><td>Default {@code 30}.</td></tr>
 *   <tr><td>{@code batchSize}</td><td>Documents per request. Default {@code 100}.</td></tr>
 * </table>
 */
public final class CchExchangeWriter implements ExchangeWriter {

    /** The wire format this writer speaks. */
    public static final ExchangeType TYPE = ExchangeType.of("cch-http");

    static final String HEADER_TOKEN = "X-NiemVault-Token";
    static final String SETTING_ENDPOINT = "endpoint";
    static final String SETTING_TOKEN_ENV = "tokenEnv";
    static final String SETTING_TIMEOUT_SECONDS = "timeoutSeconds";
    static final String SETTING_BATCH_SIZE = "batchSize";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;
    private final Clock clock;

    private ExchangeDefinition definition;
    private URI endpoint;
    private String token;
    private Duration timeout;
    private int batchSize;

    public CchExchangeWriter() {
        this(HttpClient.newHttpClient(), Clock.systemUTC());
    }

    public CchExchangeWriter(HttpClient http, Clock clock) {
        this.http = http;
        this.clock = clock;
    }

    @Override
    public ExchangeType type() {
        return TYPE;
    }

    @Override
    public void configure(ExchangeDefinition exchangeDefinition) {
        List<String> problems = new ArrayList<>();

        URI configuredEndpoint = null;
        try {
            configuredEndpoint = URI.create(exchangeDefinition.requiredSetting(SETTING_ENDPOINT));
            if (configuredEndpoint.getScheme() == null || configuredEndpoint.getHost() == null) {
                problems.add("'endpoint' must be an absolute URL, found '" + configuredEndpoint + "'");
            }
        } catch (ExchangeDefinitionException missing) {
            problems.add("'endpoint' is required: there is nowhere to submit to");
        } catch (IllegalArgumentException malformed) {
            problems.add("'endpoint' is not a usable URL: " + malformed.getMessage());
        }

        // The variable's name, never the token. A definition is a versioned artifact and belongs in
        // a repository, which is the one place a credential must not be (ADR 0015).
        String configuredToken = exchangeDefinition.setting(SETTING_TOKEN_ENV)
                .map(System::getenv)
                .orElse(null);

        int configuredTimeout = intSetting(exchangeDefinition, SETTING_TIMEOUT_SECONDS, 30, problems);
        int configuredBatch = intSetting(exchangeDefinition, SETTING_BATCH_SIZE, 100, problems);

        if (!problems.isEmpty()) {
            throw new ExchangeDefinitionException(null, problems);
        }

        this.definition = exchangeDefinition;
        this.endpoint = configuredEndpoint;
        this.token = configuredToken;
        this.timeout = Duration.ofSeconds(configuredTimeout);
        this.batchSize = configuredBatch;
    }

    private static int intSetting(
            ExchangeDefinition definition, String key, int fallback, List<String> problems) {
        return definition.setting(key).map(raw -> {
            try {
                int value = Integer.parseInt(raw);
                if (value <= 0) {
                    problems.add("'" + key + "' must be positive, found " + value);
                }
                return value;
            } catch (NumberFormatException e) {
                problems.add("'" + key + "' must be a whole number, found '" + raw + "'");
                return fallback;
            }
        }).orElse(fallback);
    }

    @Override
    public List<SubmissionReceipt> submit(List<AssembledDocument> documents) {
        requireConfigured();
        List<SubmissionReceipt> receipts = new ArrayList<>(documents.size());
        for (int from = 0; from < documents.size(); from += batchSize) {
            List<AssembledDocument> batch =
                    documents.subList(from, Math.min(from + batchSize, documents.size()));
            receipts.addAll(send(batch));
        }
        return List.copyOf(receipts);
    }

    private List<SubmissionReceipt> send(List<AssembledDocument> batch) {
        Instant now = clock.instant();
        ObjectNode body = json.createObjectNode();
        body.put("exchange", definition.exchangeName());
        body.put("exchangeVersion", definition.version());
        body.put("sourceId", definition.sourceId());
        ArrayNode array = body.putArray("documents");
        batch.forEach(document -> array.add(DocumentJson.of(document)));

        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if (token != null && !token.isBlank()) {
                request.header(HEADER_TOKEN, token);
            }
            HttpResponse<String> response =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return receiptsFrom(batch, response, now);
        } catch (IOException e) {
            throw new ExchangeDefinitionException(null, List.of(
                    "cannot reach the repository at " + endpoint + ": " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExchangeDefinitionException(null, List.of(
                    "submission to " + endpoint + " was interrupted"));
        }
    }

    /**
     * Reads the repository's answer into one receipt per document.
     *
     * <p>A response that says nothing about a document leaves it {@code PENDING} rather than
     * accepted. Treating silence as success is how a submission that was quietly dropped becomes a
     * criminal history entry this platform believes exists.
     */
    private List<SubmissionReceipt> receiptsFrom(
            List<AssembledDocument> batch, HttpResponse<String> response, Instant now) {

        int status = response.statusCode();
        if (status >= 400) {
            String detail = "HTTP " + status + ": " + abbreviate(response.body());
            return batch.stream()
                    .map(document -> SubmissionReceipt.rejected(identityOf(document), detail, now))
                    .toList();
        }

        JsonNode answered = parseReceipts(response.body());
        List<SubmissionReceipt> receipts = new ArrayList<>(batch.size());
        for (AssembledDocument document : batch) {
            String id = identityOf(document);
            JsonNode entry = answered == null ? null : answered.get(id);
            if (entry == null) {
                receipts.add(SubmissionReceipt.pending(id, null, now));
                continue;
            }
            String outcome = entry.path("outcome").asText("pending").toLowerCase(java.util.Locale.ROOT);
            String remoteId = entry.path("remoteId").asText(null);
            receipts.add(switch (outcome) {
                case "accepted" -> SubmissionReceipt.accepted(id, remoteId, now);
                case "rejected" -> SubmissionReceipt.rejected(
                        id, entry.path("reason").asText("no reason given"), now);
                default -> SubmissionReceipt.pending(id, remoteId, now);
            });
        }
        return receipts;
    }

    private JsonNode parseReceipts(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = json.readTree(body);
            JsonNode receipts = root.get("receipts");
            return receipts != null && receipts.isObject() ? receipts : null;
        } catch (IOException unreadable) {
            // A body that cannot be parsed leaves every document pending, which is the safe
            // reading: the repository may well have taken them.
            return null;
        }
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.strip();
        return trimmed.length() <= 400 ? trimmed : trimmed.substring(0, 400) + "...";
    }

    private static String identityOf(AssembledDocument document) {
        Object raw = document.root().raw(
                gov.niemplatform.canonical.meta.CanonicalTypeDescriptor.CANONICAL_ID_FIELD);
        return raw == null ? "" : String.valueOf(raw);
    }

    @Override
    public ExchangeHealth health() {
        Instant now = clock.instant();
        if (definition == null) {
            return ExchangeHealth.notConfigured(now);
        }
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody());
            if (token != null && !token.isBlank()) {
                request.header(HEADER_TOKEN, token);
            }
            HttpResponse<Void> response =
                    http.send(request.build(), HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                return ExchangeHealth.unavailable(
                        "the repository refused this platform's credential (HTTP " + status + ")", now);
            }
            if (status >= 500) {
                return ExchangeHealth.degraded(
                        "the repository answered HTTP " + status, now);
            }
            return ExchangeHealth.healthy(now);
        } catch (IOException e) {
            return ExchangeHealth.unavailable(
                    "cannot reach " + endpoint + ": " + e.getMessage(), now);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExchangeHealth.unavailable("health probe interrupted", now);
        }
    }

    @Override
    public void close() {
        // Nothing held between submissions: the JDK client pools its own connections.
    }

    private void requireConfigured() {
        if (definition == null) {
            throw new IllegalStateException("CchExchangeWriter.submit() called before configure()");
        }
    }

    /** Only what an operator needs, and never the token. */
    @Override
    public String toString() {
        return "CchExchangeWriter[" + (endpoint == null ? "<unconfigured>" : endpoint)
                + ", authenticated=" + (token != null && !token.isBlank()) + "]";
    }
}
