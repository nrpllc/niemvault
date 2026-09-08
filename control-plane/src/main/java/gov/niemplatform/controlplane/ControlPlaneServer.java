package gov.niemplatform.controlplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gov.niemplatform.content.ModuleManifest;
import gov.niemplatform.runtime.engine.HopDefinition;
import gov.niemplatform.runtime.engine.MappingDefinition;
import gov.niemplatform.runtime.transforms.TransformFactory;
import gov.niemplatform.runtime.transforms.TransformSpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Serves the mapping authoring surface (ADR 0021).
 *
 * <p>The JDK's own HTTP server, no framework. The surface is one page and a handful of endpoints,
 * and every dependency added here is a dependency that has to be mirrored into an air-gapped
 * package for every agency (§6). It binds to loopback: this is an authoring tool, not a service,
 * and Phase 1 has no authentication beyond a stub (§8).
 *
 * <p>Every endpoint that judges a mapping delegates to the loaders the runtime uses. The server
 * decides nothing about validity — that is the point of ADR 0021, and why an editor cannot drift
 * from the engine.
 */
public final class ControlPlaneServer implements AutoCloseable {

    private static final String UI_ROOT = "/ui/";

    private final HttpServer server;
    private final MappingWorkspace workspace;
    private final ObjectMapper json = new ObjectMapper();
    private final gov.niemplatform.controlplane.advice.MappingAdvisor advisor =
            new gov.niemplatform.controlplane.advice.DeterministicAdvisor();

    /**
     * Bronze, when the operator pointed at one.
     *
     * <p>Optional on purpose. A mapping is often written before a single file has landed, and the
     * advisor works without shapes — less confidently, and it says so.
     */
    private Path bronzeRoot;

    public ControlPlaneServer(MappingWorkspace workspace, int port) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        try {
            // Loopback only. An authoring tool with no authentication must not be reachable from
            // anywhere else on the network.
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot bind the control plane to port " + port, e);
        }

        server.createContext("/", this::serveUi);
        server.createContext("/api/module", exchange -> respond(exchange, this::module));
        server.createContext("/api/mappings", exchange -> respond(exchange, this::mappings));
        server.createContext("/api/mapping", exchange -> respond(exchange, this::mapping));
        server.createContext("/api/validate", exchange -> respond(exchange, this::validate));
        server.createContext("/api/edit", exchange -> respond(exchange, this::edit));
        server.createContext("/api/transforms", exchange -> respond(exchange, this::transforms));
        server.createContext("/api/suggest", exchange -> respond(exchange, this::suggest));
        server.createContext("/api/catalogue", exchange -> respond(exchange, this::catalogue));
        server.createContext("/api/catalogue/edit", exchange -> respond(exchange, this::editGlossary));
        server.createContext("/api/contract", exchange -> respond(exchange, this::contract));
        server.createContext("/api/contract/edit", exchange -> respond(exchange, this::editContract));
        server.createContext("/api/save", exchange -> respond(exchange, this::save));
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String url() {
        return "http://localhost:" + port();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // --- endpoints -------------------------------------------------------

    private ObjectNode module(HttpExchange exchange) {
        ModuleManifest manifest = workspace.manifest();
        ObjectNode node = json.createObjectNode();
        node.put("name", manifest.name());
        node.put("version", manifest.version().toString());
        node.put("displayName", manifest.displayName());
        node.put("steward", manifest.steward());
        node.put("platformVersions", manifest.platformVersions().toString());
        node.put("canonicalModel", manifest.canonicalModelVersion().toString());
        node.put("root", workspace.moduleRoot().toString());
        return node;
    }

    private ObjectNode mappings(HttpExchange exchange) {
        ObjectNode node = json.createObjectNode();
        ArrayNode list = node.putArray("mappings");
        for (MappingWorkspace.MappingFile mapping : workspace.mappings()) {
            ObjectNode entry = list.addObject();
            entry.put("file", mapping.fileName());
            entry.put("name", mapping.name());
            entry.put("version", mapping.version());
            entry.put("loadable", mapping.loadable());
        }
        return node;
    }

    private ObjectNode mapping(HttpExchange exchange) {
        String file = query(exchange, "file");
        MappingDefinition definition = workspace.load(file);

        ObjectNode node = describe(definition);
        node.put("file", file);
        node.put("source", workspace.source(file));
        node.put("svg", DagSvg.render(definition));
        node.put("nextVersion", workspace.nextVersion(definition.version()));
        return node;
    }

    /**
     * Validates candidate YAML and returns the DAG for it.
     *
     * <p>Called on every change, which is why it returns the rendered graph alongside the problems:
     * an author editing a step should see the shape move as they type, not after they save.
     */
    private ObjectNode validate(HttpExchange exchange) throws IOException {
        String yaml = body(exchange);
        MappingWorkspace.ValidationReport report = workspace.validate(yaml);

        ObjectNode node = json.createObjectNode();
        node.put("valid", report.valid());
        ArrayNode problems = node.putArray("problems");
        report.problems().forEach(problems::add);

        if (report.definition() != null) {
            node.set("mapping", describe(report.definition()));
            node.put("svg", DagSvg.render(report.definition()));
        }
        return node;
    }

    /**
     * Applies one structural edit and returns the new text alongside its validation.
     *
     * <p>The edit is a text patch, not a regenerated document (see {@link MappingText}): a form
     * that rewrote the whole file would erase the commentary explaining why each step exists, which
     * is the most expensive thing in a mapping to reconstruct.
     *
     * <p>The result is validated before it is returned, so an edit that breaks the mapping shows
     * up as a problem in the editor rather than as a file that will not deploy.
     */
    private ObjectNode edit(HttpExchange exchange) throws IOException {
        ObjectNode request = (ObjectNode) json.readTree(body(exchange));
        MappingText text = new MappingText(request.get("yaml").asText());
        String hop = request.get("hop").asText();
        int step = request.path("step").asInt();

        MappingText edited = switch (request.get("op").asText()) {
            case "setField" -> text.setStepField(hop, step,
                    request.get("key").asText(), request.get("value").asText());
            case "setFrom" -> text.setStepFrom(hop, step, strings(request.get("from")));
            case "setOption" -> text.setStepOption(hop, step,
                    request.get("key").asText(), request.get("value").asText());
            case "addStep" -> text.addStep(hop,
                    request.get("target").asText(), request.get("type").asText(),
                    strings(request.path("from")), options(request.path("options")));
            case "removeStep" -> text.removeStep(hop, step);
            case "moveStep" -> text.moveStep(hop, step, request.get("delta").asInt());
            default -> throw new IllegalArgumentException(
                    "unknown edit '" + request.get("op").asText() + "'");
        };

        String yaml = edited.text();
        MappingWorkspace.ValidationReport report = workspace.validate(yaml);

        ObjectNode node = json.createObjectNode();
        node.put("yaml", yaml);
        node.put("valid", report.valid());
        ArrayNode problems = node.putArray("problems");
        report.problems().forEach(problems::add);
        if (report.definition() != null) {
            node.set("mapping", describe(report.definition()));
            node.put("svg", DagSvg.render(report.definition()));
        }
        return node;
    }

    private static java.util.Map<String, String> options(
            com.fasterxml.jackson.databind.JsonNode object) {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        object.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        return values;
    }

    private static java.util.List<String> strings(com.fasterxml.jackson.databind.JsonNode array) {
        java.util.List<String> values = new java.util.ArrayList<>();
        array.forEach(element -> {
            String value = element.asText().trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        });
        return values;
    }

    /** Points the advisor at landed data, so it can read column shapes (ADR 0023). */
    public ControlPlaneServer profilingFrom(Path bronze) {
        this.bronzeRoot = bronze;
        return this;
    }

    /**
     * Proposes steps for canonical fields the hop does not yet produce.
     *
     * <p>Proposals only. Nothing here writes: every suggestion is applied by a person through the
     * same edit path a hand-made change takes, and validated by the same loaders (ADR 0023).
     */
    private ObjectNode suggest(HttpExchange exchange) throws IOException {
        ObjectNode request = (ObjectNode) json.readTree(body(exchange));
        String hopId = request.get("hop").asText();
        MappingWorkspace.ValidationReport report = workspace.validate(request.get("yaml").asText());

        ObjectNode node = json.createObjectNode();
        ArrayNode suggestions = node.putArray("suggestions");
        node.put("advisor", advisor.id());
        if (report.definition() == null) {
            return node;
        }

        MappingDefinition definition = report.definition();
        HopDefinition hop = definition.hops().stream()
                .filter(candidate -> candidate.hopId().equals(hopId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no hop '" + hopId + "'"));

        var target = ArtifactTypes.byName(hop.identity().entityType());
        if (target.isEmpty()) {
            return node;
        }

        var shapes = shapes(definition);
        node.put("shapesObserved", shapes.size());

        var context = new gov.niemplatform.controlplane.advice.MappingAdvisor.Context(
                definition.decoder().columns(),
                shapes,
                target.get(),
                hop.steps().stream().map(TransformSpec::target).toList(),
                List.copyOf(TransformFactory.TYPES));

        for (var suggestion : advisor.suggest(context)) {
            ObjectNode entry = suggestions.addObject();
            entry.put("target", suggestion.target());
            entry.put("type", suggestion.transformType());
            ArrayNode from = entry.putArray("from");
            suggestion.from().forEach(from::add);
            ObjectNode options = entry.putObject("options");
            suggestion.options().forEach(options::put);
            entry.put("confidence", suggestion.confidence());
            entry.put("rationale", suggestion.rationale());
        }
        return node;
    }

    /** Column shapes from what has landed, or none if no bronze was given. */
    private java.util.Map<String, gov.niemplatform.observability.ValueShape> shapes(
            MappingDefinition definition) {
        if (bronzeRoot == null) {
            return java.util.Map.of();
        }
        try (var bronze = gov.niemplatform.storage.parquet.ParquetBronzeStore.openExisting(bronzeRoot)) {
            return new gov.niemplatform.controlplane.advice.ColumnProfiler()
                    .profile(bronze, definition);
        } catch (RuntimeException unreadable) {
            // No shapes is a supported state, and losing the suggestions entirely because bronze is
            // unreadable would be a worse answer than weaker suggestions.
            return java.util.Map.of();
        }
    }

    /**
     * What the source sends, what the agency calls it, and what it becomes (§4.8, ADR 0019).
     *
     * <p>Built from the draft in the editor rather than from disk, so a term documented a moment ago
     * appears immediately. The catalogue is where meaning is captured, not only where it is read.
     */
    private ObjectNode catalogue(HttpExchange exchange) throws IOException {
        MappingWorkspace.ValidationReport report = workspace.validate(body(exchange));
        ObjectNode node = json.createObjectNode();
        if (report.definition() == null) {
            node.put("available", false);
            return node;
        }

        java.util.Map<String, gov.niemplatform.contracts.HopContract> byHop =
                new java.util.LinkedHashMap<>();
        try {
            workspace.contracts().forEach(contract -> byHop.put(contract.hopId(), contract));
        } catch (RuntimeException unreadable) {
            // Contracts that will not load are reported by validation. The vocabulary is still
            // worth showing without them.
        }

        Catalogue.Source source = Catalogue.of(
                report.definition(), byHop, gov.niemplatform.canonical.core.CoreCanonicalTypes.ALL);

        node.put("available", true);
        node.put("sourceId", source.sourceId());
        node.put("mapping", source.mappingName() + "@" + source.mappingVersion());
        node.put("recordType", source.recordType());

        ArrayNode contracts = node.putArray("contracts");
        source.contracts().forEach(contracts::add);

        ArrayNode vocabulary = node.putArray("vocabulary");
        for (Catalogue.Term term : source.vocabulary()) {
            ObjectNode entry = vocabulary.addObject();
            entry.put("term", term.term());
            entry.put("meaning", term.meaning().orElse(""));
            entry.put("documented", term.meaning().isPresent());
            entry.put("governed", term.governed());
            ArrayNode becomes = entry.putArray("becomes");
            term.becomes().forEach(becomes::add);
        }

        ArrayNode produces = node.putArray("produces");
        for (Catalogue.Produced produced : source.produces()) {
            ObjectNode entry = produces.addObject();
            entry.put("type", produced.name());
            entry.put("version", produced.version());
            entry.put("provenance", produced.provenance());
            entry.put("identity", produced.identity());
            entry.put("byHop", produced.byHop());
        }

        ObjectNode gaps = node.putObject("gaps");
        ArrayNode undocumented = gaps.putArray("undocumented");
        source.undocumented().forEach(undocumented::add);
        ArrayNode unused = gaps.putArray("unused");
        source.unused().forEach(unused::add);
        return node;
    }

    /** Records what the agency means by a term, into the mapping that declares it. */
    private ObjectNode editGlossary(HttpExchange exchange) throws IOException {
        ObjectNode request = (ObjectNode) json.readTree(body(exchange));
        String yaml = new MappingText(request.get("yaml").asText())
                .setColumnDoc(request.get("term").asText(), request.get("meaning").asText())
                .text();

        MappingWorkspace.ValidationReport report = workspace.validate(yaml);
        ObjectNode node = json.createObjectNode();
        node.put("yaml", yaml);
        node.put("valid", report.valid());
        ArrayNode problems = node.putArray("problems");
        report.problems().forEach(problems::add);
        if (report.definition() != null) {
            node.set("mapping", describe(report.definition()));
            node.put("svg", DagSvg.render(report.definition()));
        }
        return node;
    }

    /** The contract gating a hop, as the editor needs to show it. */
    private ObjectNode contract(HttpExchange exchange) {
        String hopId = query(exchange, "hop");
        var contract = contractFor(hopId);
        ObjectNode node = json.createObjectNode();
        if (!(contract instanceof gov.niemplatform.contracts.SchemaHopContract schema)) {
            node.put("present", false);
            return node;
        }

        node.put("present", true);
        node.put("name", schema.id().name());
        node.put("version", schema.id().version());
        node.put("file", workspace.contractFileFor(hopId).map(path -> path.getFileName().toString())
                .orElse(""));
        node.put("allowUnexpectedFields", schema.expects().allowUnexpectedFields());

        ArrayNode expects = node.putArray("expects");
        for (var field : schema.expects().fields()) {
            ObjectNode entry = expects.addObject();
            entry.put("name", field.name());
            entry.put("type", field.type().name().toLowerCase(java.util.Locale.ROOT));
            entry.put("required", field.required());
            entry.put("repeated", field.repeated());
            entry.put("pattern", field.pattern() == null ? "" : field.pattern());
            ArrayNode codes = entry.putArray("codeList");
            field.codeList().forEach(codes::add);
        }
        return node;
    }

    /**
     * Applies one edit to a contract and writes it.
     *
     * <p>Written in place rather than versioned up, unlike a mapping. A contract describes what a
     * source actually sends; once the source changes, the previous description is not something
     * anyone wants left running. Bumping the contract's own version when a change is breaking is a
     * judgement the author makes, not the editor.
     */
    private ObjectNode editContract(HttpExchange exchange) throws IOException {
        ObjectNode request = (ObjectNode) json.readTree(body(exchange));
        String hopId = request.get("hop").asText();
        ContractText text = new ContractText(workspace.contractSource(hopId));
        ContractText.Side side = ContractText.Side.valueOf(
                request.path("side").asText("EXPECTS").toUpperCase(java.util.Locale.ROOT));

        ContractText edited = switch (request.get("op").asText()) {
            case "setAttribute" -> text.setFieldAttribute(side,
                    request.get("field").asText(), request.get("key").asText(),
                    request.get("value").asText());
            case "addField" -> text.addField(side,
                    request.get("field").asText(), request.path("type").asText("string"));
            case "removeField" -> text.removeField(side, request.get("field").asText());
            default -> throw new IllegalArgumentException(
                    "unknown contract edit '" + request.get("op").asText() + "'");
        };

        workspace.writeContract(hopId, edited.text());

        ObjectNode node = json.createObjectNode();
        node.put("saved", true);
        // The mapping is revalidated too: a contract edit can create or close a coverage hole in a
        // mapping nobody touched, and the author should see that immediately.
        MappingWorkspace.ValidationReport report = workspace.validate(request.get("yaml").asText());
        node.put("valid", report.valid());
        ArrayNode problems = node.putArray("problems");
        report.problems().forEach(problems::add);
        if (report.definition() != null) {
            node.set("mapping", describe(report.definition()));
            node.put("svg", DagSvg.render(report.definition()));
        }
        return node;
    }

    /** The transform vocabulary an author can choose from, straight from the factory. */
    private ObjectNode transforms(HttpExchange exchange) {
        ObjectNode node = json.createObjectNode();
        ArrayNode types = node.putArray("types");
        TransformFactory.TYPES.stream().sorted().forEach(types::add);
        return node;
    }

    private ObjectNode save(HttpExchange exchange) throws IOException {
        ObjectNode request = (ObjectNode) json.readTree(body(exchange));
        String yaml = request.get("yaml").asText();

        MappingWorkspace.ValidationReport report = workspace.validate(yaml);
        ObjectNode node = json.createObjectNode();
        if (!report.valid()) {
            // Refused rather than saved with a warning. A mapping on disk that does not load is a
            // deployment that fails at start-up, and the editor is the last place to catch it.
            node.put("saved", false);
            ArrayNode problems = node.putArray("problems");
            report.problems().forEach(problems::add);
            return node;
        }

        MappingDefinition definition = report.definition();
        Path written = workspace.saveAsNewVersion(yaml, definition.name(), definition.version());
        node.put("saved", true);
        node.put("file", written.getFileName().toString());
        node.put("qualifiedName", definition.qualifiedName());
        return node;
    }

    // --- shaping ---------------------------------------------------------

    private ObjectNode describe(MappingDefinition definition) {
        ObjectNode node = json.createObjectNode();
        node.put("name", definition.name());
        node.put("version", definition.version());
        node.put("sourceId", definition.sourceId());

        ArrayNode columns = node.putArray("columns");
        definition.decoder().columns().forEach(columns::add);

        ArrayNode hops = node.putArray("hops");
        for (HopDefinition hop : definition.hopsInDependencyOrder()) {
            ObjectNode entry = hops.addObject();
            entry.put("id", hop.hopId());
            entry.put("contract", hop.contractName() + "@" + hop.contractVersion());
            entry.put("entityType", hop.identity().entityType());
            entry.put("identityMode", hop.identity().mode().name());
            entry.put("identityDetail", hop.identity().mode() == gov.niemplatform.runtime.engine
                    .IdentitySpec.Mode.RESOLVE
                    ? hop.identity().providerId()
                    : String.join(" + ", hop.identity().deriveFrom()));

            ArrayNode dependsOn = entry.putArray("dependsOn");
            hop.dependsOn().forEach(dependsOn::add);
            ArrayNode scratch = entry.putArray("scratch");
            hop.scratch().forEach(scratch::add);

            ArrayNode steps = entry.putArray("steps");
            for (TransformSpec step : hop.steps()) {
                ObjectNode stepNode = steps.addObject();
                stepNode.put("target", step.target());
                stepNode.put("type", step.type());
                ArrayNode from = stepNode.putArray("from");
                step.from().forEach(from::add);
                ObjectNode options = stepNode.putObject("options");
                step.options().forEach(options::put);
                stepNode.put("scratch", hop.scratch().contains(step.target()));
            }

            // The field-level flow travels with the hop. It is what the canvas draws, and computing
            // it here rather than in the browser keeps the single-assignment rule -- the thing that
            // makes a re-assigned target a chain instead of a cycle -- somewhere it can be tested.
            entry.set("graph", graph(FieldGraph.of(definition, hop.hopId(), contractFor(hop.hopId()))));
        }
        return node;
    }

    /** The contract gating a hop, or null when the module carries none for it. */
    private gov.niemplatform.contracts.HopContract contractFor(String hopId) {
        try {
            return workspace.contracts().stream()
                    .filter(contract -> contract.hopId().equals(hopId))
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException unreadable) {
            // Contracts that will not load are reported as problems by validation. Losing the
            // drawing as well would leave an author with nothing to work from.
            return null;
        }
    }

    private ObjectNode graph(FieldGraph graph) {
        ObjectNode node = json.createObjectNode();
        ArrayNode nodes = node.putArray("nodes");
        for (FieldGraph.Node graphNode : graph.nodes()) {
            ObjectNode entry = nodes.addObject();
            entry.put("id", graphNode.id());
            entry.put("kind", graphNode.kind().name().toLowerCase(java.util.Locale.ROOT));
            entry.put("label", graphNode.label());
            entry.put("detail", graphNode.detail());
            entry.put("step", graphNode.stepIndex());
            entry.put("terminal", graphNode.terminal());
        }
        ArrayNode edges = node.putArray("edges");
        for (FieldGraph.Edge edge : graph.edges()) {
            ObjectNode entry = edges.addObject();
            entry.put("from", edge.from());
            entry.put("to", edge.to());
            entry.put("label", edge.label());
        }
        return node;
    }

    // --- plumbing --------------------------------------------------------

    @FunctionalInterface
    private interface Handler {
        ObjectNode handle(HttpExchange exchange) throws IOException;
    }

    private void respond(HttpExchange exchange, Handler handler) throws IOException {
        try {
            ObjectNode result = handler.handle(exchange);
            send(exchange, 200, "application/json", json.writeValueAsBytes(result));
        } catch (RuntimeException | IOException e) {
            // The message is the useful part: these are validation and content errors an author
            // needs to read, not stack traces.
            ObjectNode error = json.createObjectNode();
            error.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            send(exchange, 400, "application/json", json.writeValueAsBytes(error));
        }
    }

    private void serveUi(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String resource = "/".equals(path) ? "index.html" : path.substring(1);

        // Anything with a path separator or traversal is refused: this reads from the jar, and a
        // request is not permitted to choose which part of it.
        if (resource.contains("..") || resource.contains("/")) {
            send(exchange, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream stream = getClass().getResourceAsStream(UI_ROOT + resource)) {
            if (stream == null) {
                send(exchange, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(exchange, 200, contentType(resource), stream.readAllBytes());
        }
    }

    private static String contentType(String resource) {
        if (resource.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (resource.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (resource.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        return "application/octet-stream";
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String body(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String query(HttpExchange exchange, String key) {
        String raw = exchange.getRequestURI().getQuery();
        if (raw == null) {
            throw new IllegalArgumentException("'" + key + "' is required");
        }
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals(key)) {
                return java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        throw new IllegalArgumentException("'" + key + "' is required");
    }

    /** Convenience for the CLI: open a module and start serving. */
    public static ControlPlaneServer open(
            Path moduleRoot, gov.niemplatform.content.SemanticVersion platformVersion, int port) {
        ControlPlaneServer server = new ControlPlaneServer(
                new MappingWorkspace(moduleRoot, platformVersion), port);
        server.start();
        return server;
    }
}
