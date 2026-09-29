package gov.niemplatform.projections.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalFieldDescriptor;
import gov.niemplatform.canonical.meta.CanonicalId;
import gov.niemplatform.canonical.meta.CanonicalKind;
import gov.niemplatform.canonical.meta.CanonicalRef;
import gov.niemplatform.canonical.meta.CanonicalRoleDescriptor;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.projections.api.CanonicalChangeSet;
import gov.niemplatform.projections.api.CanonicalSnapshot;
import gov.niemplatform.projections.api.ProjectionContext;
import gov.niemplatform.projections.api.ProjectionException;
import gov.niemplatform.projections.api.ProjectionException.Operation;
import gov.niemplatform.projections.api.ProjectionType;
import gov.niemplatform.projections.api.ProjectionWriter;
import gov.niemplatform.projections.api.TypedRecords;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Projects canonical silver into Elasticsearch (spec §4.6, ADR 0007, ADR 0036).
 *
 * <h2>How the canonical model becomes documents</h2>
 *
 * <ul>
 *   <li>Every canonical type gets its own alias, {@code {prefix}-{tenant}-{type}}, backed by one
 *       timestamped index with an explicit, {@code strict} mapping built from the descriptor. A
 *       field the model does not declare is refused, not guessed at: a guessed mapping is fixed for
 *       the life of the index, and the first value to arrive decides whether a date is a date.
 *   <li>A canonical <strong>entity</strong> is a document keyed by its canonical identity.
 *   <li>A canonical <strong>association</strong> is a document in its own alias, and is also
 *       written onto each endpoint as an entry in that entity's {@code links}. Search is
 *       entity-first -- an investigator looks for a person, then asks what they were on -- and a
 *       link carried on the entity answers "incidents where this person was a suspect" in one
 *       query instead of a join the cluster cannot do.
 * </ul>
 *
 * <h2>Same semantics as the other projections</h2>
 *
 * <p>An entity write is a partial update with {@code doc_as_upsert}: fields present are set, fields
 * absent are left alone. That is what the graph writer's {@code SET n += props} does, and it has to
 * be. Two projections that disagree about what a record with a missing field means will disagree
 * about the record, and §4.7's {@code ProjectionDivergence} would then be reporting the writers,
 * not the data.
 *
 * <p>A link written onto an endpoint that does not exist fails, as the graph writer's does. An
 * upsert would create a document with nothing in it but a link, which a search then returns as a
 * person with no name.
 *
 * <h2>A 200 is not a success</h2>
 *
 * <p>{@code _bulk} answers HTTP 200 when some or all of its actions were refused, and says so in a
 * flag in the body. Reading only the status is the most natural way to write a bulk client and is
 * exactly how records disappear from search without anything reporting it. Every response is read
 * item by item.
 */
public final class ElasticsearchProjectionWriter implements ProjectionWriter {

    private static final Logger LOG = LoggerFactory.getLogger(ElasticsearchProjectionWriter.class);

    /** Actions per {@code _bulk} request: bounded request size, few enough round trips. */
    private static final int ACTIONS_PER_REQUEST = 1_000;

    /** Item errors quoted in an exception. The rest are counted, not listed. */
    private static final int ERRORS_REPORTED = 5;

    /** Members the projection adds to every document, so the model may not declare them. */
    static final String SEARCH_TEXT = "searchText";
    static final String LINKS = "links";
    static final String LINEAGE = "niem";
    private static final Set<String> RESERVED = Set.of(SEARCH_TEXT, LINKS, LINEAGE);

    /**
     * Replaces this association's link to this counterpart, or adds it.
     *
     * <p>Remove-then-add keyed on association, role and counterpart is what makes re-applying a
     * change set leave one link rather than two. Keyed on the counterpart as well as the
     * association because an association with more than two roles leaves one link per counterpart
     * on each endpoint.
     */
    static final String LINK_SCRIPT = """
            if (ctx._source.links == null) { ctx._source.links = new ArrayList(); }
            ctx._source.links.removeIf(l -> l.associationId == params.link.associationId
                && l.role == params.link.role && l.otherId == params.link.otherId);
            ctx._source.links.add(params.link);
            """;

    private final SearchHttp http;
    private final ObjectMapper json;
    private final String prefix;
    private final String refresh;
    private final ProjectionContext context;
    private final Clock clock;
    private final Map<String, CanonicalTypeDescriptor> model = new LinkedHashMap<>();

    /**
     * Opens the projection: reachable, claimed for this tenant, and every type's index declared.
     *
     * <p>All of it before a record is written. An index created lazily by the first write gets
     * whatever mapping that write implies, and a tenant checked lazily is checked after the data
     * it should have refused is already in the index.
     */
    ElasticsearchProjectionWriter(
            SearchHttp http, ObjectMapper json, String prefix, String refresh,
            ProjectionContext context, Clock clock) {
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        this.context = Objects.requireNonNull(context, "context");
        this.clock = Objects.requireNonNull(clock, "clock");
        context.model().forEach(descriptor -> model.put(descriptor.name(), descriptor));
        try {
            verifyConnectivity();
            claimForTenant();
            model.values().forEach(this::declare);
        } catch (RuntimeException e) {
            http.close();
            if (e instanceof ProjectionException) {
                throw e;
            }
            throw new ProjectionException(Operation.CONNECT, ProjectionType.SEARCH, null,
                    "could not open " + http.base(), e);
        }
    }

    @Override
    public ProjectionType type() {
        return ProjectionType.SEARCH;
    }

    // --- open --------------------------------------------------------------

    private void verifyConnectivity() {
        SearchHttp.Response response;
        try {
            response = http.send("GET", "/", null);
        } catch (RuntimeException e) {
            throw new ProjectionException(Operation.CONNECT, ProjectionType.SEARCH, null,
                    "could not connect to " + http.base(), e);
        }
        if (!response.ok()) {
            throw new ProjectionException(Operation.CONNECT, ProjectionType.SEARCH, null,
                    "could not connect to %s: %s".formatted(http.base(), response.describe()), null);
        }
    }

    /**
     * Claims the cluster's indices for this tenant, or verifies an existing claim (ADR 0026).
     *
     * <p>The same check the bronze store makes, for the same reason: two agencies' records in one
     * index are not a condition to detect later. The claim is created with {@code _create}, so two
     * writers opening an empty cluster at once cannot both succeed in claiming it for different
     * agencies -- the second is told it already exists and checks whose it is.
     */
    private void claimForTenant() {
        String claimIndex = SearchNaming.claimIndex(prefix);
        String tenant = context.tenant().value();

        SearchHttp.Response existing = http.send("GET", "/" + claimIndex + "/_doc/claim", null);
        if (existing.ok() && existing.body().path("found").asBoolean()) {
            verifyClaim(existing.body().path("_source").path("tenant").asText());
            return;
        }
        if (existing.status() != 404) {
            throw new ProjectionException(Operation.CONNECT, ProjectionType.SEARCH, null,
                    "could not read the tenant claim: " + existing.describe(), null);
        }

        // Only claim indices that hold nothing. Documents under this prefix with no claim predate
        // the check, and stamping an agency's name on data of unknown origin is worse than refusing.
        SearchHttp.Response held = http.send("GET", "/" + prefix
                + "-*/_count?allow_no_indices=true&ignore_unavailable=true&expand_wildcards=open", null);
        if (!held.ok()) {
            throw new ProjectionException(Operation.CONNECT, ProjectionType.SEARCH, null,
                    "could not check for unclaimed indices: " + held.describe(), null);
        }
        long documents = held.body().path("count").asLong();
        if (documents > 0) {
            throw new ProjectionException(Operation.INTEGRITY, ProjectionType.SEARCH, null,
                    ("indices under '%s-' already hold %,d document(s) but do not say whose they are. "
                            + "Claiming them for '%s' would assert an origin nobody recorded")
                            .formatted(prefix, documents, tenant), null);
        }

        ObjectNode claim = json.createObjectNode()
                .put("tenant", tenant)
                .put("claimedAt", clock.instant().toString());
        SearchHttp.Response created =
                http.send("PUT", "/" + claimIndex + "/_create/claim?refresh=true", claim);
        if (created.ok()) {
            return;
        }
        if (created.status() == 409) {
            SearchHttp.Response raced = http.send("GET", "/" + claimIndex + "/_doc/claim", null);
            verifyClaim(raced.body().path("_source").path("tenant").asText());
            return;
        }
        throw new ProjectionException(Operation.CONNECT, ProjectionType.SEARCH, null,
                "could not record the tenant claim: " + created.describe(), null);
    }

    private void verifyClaim(String claimed) {
        String tenant = context.tenant().value();
        if (!claimed.equals(tenant)) {
            throw new ProjectionException(Operation.INTEGRITY, ProjectionType.SEARCH, null,
                    ("the '%s' indices on this cluster belong to '%s' and cannot also hold data for "
                            + "'%s'. A deployment serves one agency (ADR 0026); give this tenant its "
                            + "own cluster").formatted(prefix, claimed, tenant), null);
        }
    }

    /**
     * Makes a type's alias exist with the model's mapping.
     *
     * <p>A new type gets a backing index. An existing one has the full mapping put again: fields the
     * model has added since are added to the index, and a field whose type changed is refused by
     * the cluster, which is right -- a text field cannot become a date in place, and the answer is
     * a rebuild, not a quietly mis-typed field.
     */
    private void declare(CanonicalTypeDescriptor descriptor) {
        String alias = alias(descriptor.name());
        List<String> behind = indicesBehind(alias);
        if (behind.isEmpty()) {
            createIndex(SearchNaming.backingIndex(alias, clock.instant()), descriptor, alias);
            return;
        }
        SearchHttp.Response response = http.send("PUT", "/" + alias + "/_mapping", mapping(descriptor));
        if (!response.ok()) {
            throw new ProjectionException(Operation.CONNECT, ProjectionType.SEARCH, descriptor.name(),
                    "the existing index does not accept the model's mapping; a changed field type "
                            + "needs a rebuild: " + response.describe(), null);
        }
    }

    private void createIndex(String index, CanonicalTypeDescriptor descriptor, String alias) {
        ObjectNode body = json.createObjectNode();
        body.set("mappings", mapping(descriptor));
        if (alias != null) {
            body.putObject("aliases").putObject(alias).put("is_write_index", true);
        }
        SearchHttp.Response response = http.send("PUT", "/" + index, body);
        if (!response.ok()) {
            throw new ProjectionException(Operation.CONNECT, ProjectionType.SEARCH, descriptor.name(),
                    "could not create index " + index + ": " + response.describe(), null);
        }
    }

    private List<String> indicesBehind(String alias) {
        SearchHttp.Response response = http.send("GET", "/_alias/" + alias, null);
        if (response.status() == 404) {
            return List.of();
        }
        if (!response.ok()) {
            throw new IllegalStateException("could not resolve alias " + alias + ": " + response.describe());
        }
        List<String> indices = new ArrayList<>();
        response.body().fieldNames().forEachRemaining(indices::add);
        return indices;
    }

    // --- mapping -------------------------------------------------------------

    /**
     * The index mapping a canonical type implies.
     *
     * <p>DECIMAL is mapped as {@code double}, which loses precision past fifteen significant digits.
     * Search needs range queries over it and has no exact decimal type to offer; the operational
     * data store holds the exact value, and the canonical model remains the authority on it.
     */
    ObjectNode mapping(CanonicalTypeDescriptor descriptor) {
        ObjectNode mapping = json.createObjectNode().put("dynamic", "strict");
        ObjectNode properties = mapping.putObject("properties");

        properties.putObject(CanonicalTypeDescriptor.CANONICAL_ID_FIELD).put("type", "keyword");
        properties.putObject(SEARCH_TEXT).put("type", "text");

        ObjectNode lineage = properties.putObject(LINEAGE).putObject("properties");
        lineage.putObject("runId").put("type", "keyword");
        lineage.putObject("sourceId").put("type", "keyword");
        lineage.putObject("mapping").put("type", "keyword");
        lineage.putObject("projectedAt").put("type", "date");

        for (CanonicalRoleDescriptor role : descriptor.roles()) {
            reserved(descriptor, role.name());
            properties.putObject(role.name()).put("type", "keyword");
        }
        for (CanonicalFieldDescriptor field : descriptor.fields()) {
            reserved(descriptor, field.name());
            ObjectNode property = properties.putObject(field.name());
            switch (field.type()) {
                case STRING -> {
                    property.put("type", "text").put("copy_to", SEARCH_TEXT);
                    property.putObject("fields").putObject("keyword")
                            .put("type", "keyword").put("ignore_above", 256);
                }
                case CODE, REF, IDENTITY -> property.put("type", "keyword");
                case DATE -> property.put("type", "date").put("format", "strict_date");
                case DATE_TIME -> property.put("type", "date");
                case INTEGER -> property.put("type", "long");
                case DECIMAL -> property.put("type", "double");
                case BOOLEAN -> property.put("type", "boolean");
            }
        }

        if (descriptor.kind() == CanonicalKind.ENTITY) {
            ObjectNode links = properties.putObject(LINKS).put("type", "nested");
            ObjectNode linkProperties = links.putObject("properties");
            for (String key : List.of("association", "associationId", "role", "otherType", "otherId")) {
                linkProperties.putObject(key).put("type", "keyword");
            }
            // Carried for display, not indexed: an association's fields differ by association type,
            // and a strict mapping of their union would change every time an association was added.
            linkProperties.putObject("fields").put("type", "object").put("enabled", false);
        }
        return mapping;
    }

    private static void reserved(CanonicalTypeDescriptor descriptor, String member) {
        if (RESERVED.contains(member)) {
            throw new ProjectionException(Operation.CONNECT, ProjectionType.SEARCH, descriptor.name(),
                    "the model declares a member named '" + member + "', which the search projection "
                            + "reserves for itself " + RESERVED, null);
        }
    }

    // --- apply ---------------------------------------------------------------

    @Override
    public void apply(CanonicalChangeSet changes) {
        if (changes.isEmpty()) {
            return;
        }
        try {
            for (TypedRecords typed : ordered(changes.changes())) {
                write(typed, this::alias, changes.runId(), refresh, Operation.APPLY);
            }
        } catch (ProjectionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProjectionException(Operation.APPLY, ProjectionType.SEARCH, null,
                    "could not apply %d record(s)".formatted(changes.size()), e);
        }
    }

    /** Entities before associations: a link needs its endpoint to exist. */
    private static List<TypedRecords> ordered(List<TypedRecords> groups) {
        List<TypedRecords> entities = new ArrayList<>();
        List<TypedRecords> associations = new ArrayList<>();
        for (TypedRecords typed : groups) {
            (typed.descriptor().kind() == CanonicalKind.ASSOCIATION ? associations : entities).add(typed);
        }
        entities.addAll(associations);
        return entities;
    }

    /**
     * Writes one type's records.
     *
     * @param indexFor where a type's documents go: the alias on apply, the new backing index on
     *     rebuild
     */
    private void write(
            TypedRecords typed, Function<String, String> indexFor, String runId,
            String refreshParameter, Operation operation) {

        CanonicalTypeDescriptor descriptor = typed.descriptor();
        String ownIndex = indexFor.apply(descriptor.name());
        List<String> lines = new ArrayList<>();
        ObjectNode lineage = lineage(runId);

        for (Record record : typed.records()) {
            String identity = identityOf(record, descriptor, operation);
            ObjectNode document = document(descriptor, record, identity, lineage);

            lines.add(line(json.createObjectNode().set("update",
                    json.createObjectNode().put("_index", ownIndex).put("_id", identity))));
            ObjectNode upsert = json.createObjectNode();
            upsert.set("doc", document);
            upsert.put("doc_as_upsert", true);
            lines.add(line(upsert));

            if (descriptor.kind() == CanonicalKind.ASSOCIATION) {
                linkEndpoints(descriptor, record, identity, indexFor, operation, lines);
            }
        }

        for (int from = 0; from < lines.size(); from += ACTIONS_PER_REQUEST * 2) {
            bulk(lines.subList(from, Math.min(lines.size(), from + ACTIONS_PER_REQUEST * 2)),
                    descriptor.name(), refreshParameter, operation);
        }
    }

    /** One scripted update per endpoint and counterpart, adding this association to its links. */
    private void linkEndpoints(
            CanonicalTypeDescriptor descriptor, Record record, String identity,
            Function<String, String> indexFor, Operation operation, List<String> lines) {

        Map<String, CanonicalRef> endpoints = new LinkedHashMap<>();
        for (CanonicalRoleDescriptor role : descriptor.roles()) {
            CanonicalRef endpoint = record.get(role.name(), CanonicalRef.class);
            if (endpoint != null) {
                endpoints.put(role.name(), endpoint);
            } else if (descriptor.roles().size() == 2) {
                // The graph writer's refusal, for its reason: a half-connected association is worse
                // than none, and the mapping should not have emitted it.
                throw new ProjectionException(operation, ProjectionType.SEARCH, descriptor.name(),
                        "an association is missing its '" + role.name() + "' reference", null);
            }
        }

        ObjectNode fields = json.createObjectNode();
        for (CanonicalFieldDescriptor field : descriptor.fields()) {
            Object value = record.raw(field.name());
            if (value != null) {
                fields.set(field.name(), json.valueToTree(toSearchValue(value)));
            }
        }

        endpoints.forEach((role, endpoint) -> {
            String endpointIndex = indexFor.apply(endpoint.typeName());
            if (endpointIndex == null) {
                throw new ProjectionException(operation, ProjectionType.SEARCH, descriptor.name(),
                        "the association's '" + role + "' refers to type '" + endpoint.typeName()
                                + "', which the model this projection was opened with does not declare",
                        null);
            }
            endpoints.forEach((otherRole, other) -> {
                if (otherRole.equals(role)) {
                    return;
                }
                ObjectNode link = json.createObjectNode()
                        .put("association", descriptor.name())
                        .put("associationId", identity)
                        .put("role", role)
                        .put("otherType", SearchNaming.simpleName(other.typeName()))
                        .put("otherId", other.id().value());
                link.set("fields", fields);

                ObjectNode script = json.createObjectNode();
                script.putObject("script")
                        .put("lang", "painless")
                        .put("source", LINK_SCRIPT)
                        .putObject("params").set("link", link);

                lines.add(line(json.createObjectNode().set("update", json.createObjectNode()
                        .put("_index", endpointIndex).put("_id", endpoint.id().value()))));
                lines.add(line(script));
            });
        });
    }

    private void bulk(List<String> lines, String canonicalType, String refreshParameter, Operation operation) {
        StringBuilder ndjson = new StringBuilder();
        lines.forEach(line -> ndjson.append(line).append('\n'));

        SearchHttp.Response response = http.sendNdjson("/_bulk?refresh=" + refreshParameter, ndjson.toString());
        if (!response.ok()) {
            throw new ProjectionException(operation, ProjectionType.SEARCH, canonicalType,
                    "the bulk request was refused: " + response.describe(), null);
        }
        if (!response.body().path("errors").asBoolean()) {
            return;
        }

        int failed = 0;
        List<String> reported = new ArrayList<>();
        for (JsonNode item : response.body().path("items")) {
            JsonNode result = item.elements().next();
            if (result.path("status").asInt() < 300) {
                continue;
            }
            failed++;
            if (reported.size() < ERRORS_REPORTED) {
                JsonNode error = result.path("error");
                reported.add("%s/%s: %s: %s".formatted(
                        result.path("_index").asText(), result.path("_id").asText(),
                        error.path("type").asText("error"),
                        SearchHttp.redact(error.path("reason").asText(""))));
            }
        }
        throw new ProjectionException(operation, ProjectionType.SEARCH, canonicalType,
                "%d of %d action(s) were refused; the cluster answered 200 and the rest were written: %s"
                        .formatted(failed, lines.size() / 2, String.join("; ", reported)), null);
    }

    // --- rebuild -------------------------------------------------------------

    /**
     * Rebuilds every index from a snapshot, replacing what was there.
     *
     * <p>Built beside, then swapped. Each type gets a new backing index, the snapshot is loaded into
     * those, and a single {@code _aliases} request moves every alias from the old index to the new
     * one. Readers see the old projection until that request and the new one after it, never an
     * empty or half-loaded index -- and a rebuild that fails before the swap deletes what it built
     * and leaves the previous projection serving, because a projection that is stale is
     * recoverable and a projection that is empty looks like a search that found nothing.
     */
    @Override
    public void rebuild(CanonicalSnapshot snapshot) {
        Map<String, CanonicalTypeDescriptor> types = new LinkedHashMap<>(model);
        snapshot.contents().forEach(typed -> types.putIfAbsent(typed.descriptor().name(), typed.descriptor()));

        String stamp = SearchNaming.stamp(clock.instant());
        Map<String, String> fresh = new LinkedHashMap<>();
        boolean swapped = false;
        List<String> previous = new ArrayList<>();
        try {
            for (CanonicalTypeDescriptor descriptor : types.values()) {
                String index = alias(descriptor.name()) + "-" + stamp;
                createIndex(index, descriptor, null);
                fresh.put(descriptor.name(), index);
            }
            Function<String, String> freshIndex = typeName -> fresh.get(SearchNaming.simpleName(typeName));
            for (TypedRecords typed : ordered(snapshot.contents())) {
                write(typed, freshIndex, "rebuild", "false", Operation.REBUILD);
            }

            SearchHttp.Response refreshed =
                    http.send("POST", "/" + String.join(",", fresh.values()) + "/_refresh", null);
            if (!refreshed.ok()) {
                throw new ProjectionException(Operation.REBUILD, ProjectionType.SEARCH, null,
                        "could not refresh the rebuilt indices: " + refreshed.describe(), null);
            }

            ObjectNode request = json.createObjectNode();
            ArrayNode actions = request.putArray("actions");
            fresh.forEach((typeName, index) -> {
                String alias = alias(typeName);
                for (String old : indicesBehind(alias)) {
                    actions.addObject().putObject("remove").put("index", old).put("alias", alias);
                    previous.add(old);
                }
                actions.addObject().putObject("add")
                        .put("index", index).put("alias", alias).put("is_write_index", true);
            });
            SearchHttp.Response swap = http.send("POST", "/_aliases", request);
            if (!swap.ok()) {
                throw new ProjectionException(Operation.REBUILD, ProjectionType.SEARCH, null,
                        "could not swap the aliases to the rebuilt indices: " + swap.describe(), null);
            }
            swapped = true;
        } catch (RuntimeException e) {
            if (!swapped && !fresh.isEmpty()) {
                SearchHttp.Response discarded =
                        http.send("DELETE", "/" + String.join(",", fresh.values()), null);
                if (!discarded.ok()) {
                    LOG.warn("A failed rebuild left indices behind that could not be deleted: {} ({})",
                            fresh.values(), discarded.describe());
                }
            }
            if (e instanceof ProjectionException) {
                throw e;
            }
            throw new ProjectionException(Operation.REBUILD, ProjectionType.SEARCH, null,
                    "could not rebuild from a snapshot of %d record(s)".formatted(snapshot.size()), e);
        }

        // The new projection is already serving. Old indices that cannot be removed are storage to
        // reclaim, not a divergence, so they are reported rather than failing a rebuild that worked.
        if (!previous.isEmpty()) {
            SearchHttp.Response removed = http.send("DELETE", "/" + String.join(",", previous), null);
            if (!removed.ok()) {
                LOG.warn("Rebuild succeeded but the replaced indices could not be deleted: {} ({})",
                        previous, removed.describe());
            }
        }
    }

    // --- counts --------------------------------------------------------------

    @Override
    public long count(CanonicalTypeDescriptor descriptor) {
        try {
            SearchHttp.Response response = http.send("GET", "/" + alias(descriptor.name()) + "/_count", null);
            if (!response.ok()) {
                throw new ProjectionException(Operation.COUNT, ProjectionType.SEARCH, descriptor.name(),
                        "could not count: " + response.describe(), null);
            }
            return response.body().path("count").asLong();
        } catch (ProjectionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProjectionException(Operation.COUNT, ProjectionType.SEARCH, descriptor.name(),
                    "could not count", e);
        }
    }

    // --- documents -----------------------------------------------------------

    private String alias(String typeName) {
        return SearchNaming.alias(prefix, context.tenant(), typeName);
    }

    private ObjectNode lineage(String runId) {
        return json.createObjectNode()
                .put("runId", runId)
                .put("sourceId", context.sourceId())
                .put("mapping", context.qualifiedMapping())
                .put("projectedAt", clock.instant().toString());
    }

    private ObjectNode document(
            CanonicalTypeDescriptor descriptor, Record record, String identity, ObjectNode lineage) {
        ObjectNode document = json.createObjectNode();
        document.put(CanonicalTypeDescriptor.CANONICAL_ID_FIELD, identity);
        for (CanonicalRoleDescriptor role : descriptor.roles()) {
            CanonicalRef endpoint = record.get(role.name(), CanonicalRef.class);
            if (endpoint != null) {
                document.put(role.name(), endpoint.id().value());
            }
        }
        // Absent values are omitted, not written as null: in a partial update a null overwrites,
        // and the merge semantics above depend on absence meaning "leave it".
        for (CanonicalFieldDescriptor field : descriptor.fields()) {
            Object value = record.raw(field.name());
            if (value != null) {
                document.set(field.name(), json.valueToTree(toSearchValue(value)));
            }
        }
        document.set(LINEAGE, lineage);
        return document;
    }

    private static String identityOf(Record record, CanonicalTypeDescriptor descriptor, Operation operation) {
        CanonicalId identity = record.get(CanonicalTypeDescriptor.CANONICAL_ID_FIELD, CanonicalId.class);
        if (identity == null) {
            throw new ProjectionException(operation, ProjectionType.SEARCH, descriptor.name(),
                    "a canonical record reached the projection without an identity", null);
        }
        return identity.value();
    }

    private static Object toSearchValue(Object value) {
        if (value instanceof CanonicalId identity) {
            return identity.value();
        }
        if (value instanceof CanonicalRef reference) {
            return reference.typeName() + "/" + reference.id().value();
        }
        if (value instanceof Instant || value instanceof LocalDate) {
            return value.toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ElasticsearchProjectionWriter::toSearchValue).toList();
        }
        return value;
    }

    private String line(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise a bulk action", e);
        }
    }

    @Override
    public void close() {
        http.close();
    }

    /** Where and whose -- never what. A writer's string reaches logs (ADR 0015). */
    @Override
    public String toString() {
        return "ElasticsearchProjectionWriter[" + http.base() + ", prefix=" + prefix
                + ", tenant=" + context.tenant() + "]";
    }
}
