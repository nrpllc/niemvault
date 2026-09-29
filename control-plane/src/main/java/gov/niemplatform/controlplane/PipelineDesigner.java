package gov.niemplatform.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.niemplatform.canonical.core.CoreCanonicalTypes;
import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.canonical.meta.TenantId;
import gov.niemplatform.connectors.api.ConnectorConfigurationException;
import gov.niemplatform.connectors.api.ConnectorRegistry;
import gov.niemplatform.connectors.api.ConnectorType;
import gov.niemplatform.connectors.api.HealthStatus;
import gov.niemplatform.connectors.api.SourceCheckpointStore;
import gov.niemplatform.connectors.api.SourceConnector;
import gov.niemplatform.connectors.api.SourceDefinition;
import gov.niemplatform.connectors.api.SourceHandle;
import gov.niemplatform.contracts.HopContract;
import gov.niemplatform.contracts.QuarantineSink;
import gov.niemplatform.exchange.api.DocumentAssembler;
import gov.niemplatform.exchange.api.ExchangeDefinition;
import gov.niemplatform.exchange.api.ExchangeRegistry;
import gov.niemplatform.exchange.api.ExchangeType;
import gov.niemplatform.identity.api.InMemoryClusterIndex;
import gov.niemplatform.identity.api.IndexedResolutionProvider;
import gov.niemplatform.identity.internal.DeterministicResolutionProvider;
import gov.niemplatform.observability.ContractViolation;
import gov.niemplatform.observability.Direction;
import gov.niemplatform.observability.RecordingObservabilityEmitter;
import gov.niemplatform.pipeline.ArtifactCatalog;
import gov.niemplatform.pipeline.PipelineDefinition;
import gov.niemplatform.pipeline.PipelineDefinitionException;
import gov.niemplatform.pipeline.PipelineResolver;
import gov.niemplatform.projections.api.ProjectionFactory;
import gov.niemplatform.projections.api.ProjectionRegistry;
import gov.niemplatform.projections.api.ProjectionType;
import gov.niemplatform.runtime.engine.MappingDefinition;
import gov.niemplatform.runtime.engine.MappingPipeline;
import gov.niemplatform.settings.SettingDescriptor;
import gov.niemplatform.settings.SettingsCheck;
import gov.niemplatform.storage.api.RawEnvelope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The pipeline designer's server half (ADR 0037): a palette, validation, saving, a connection test,
 * and preview.
 *
 * <p>It decides nothing on its own about whether a pipeline is sound. Settings are checked against
 * what each component declares it reads, transports are checked by configuring a fresh connector,
 * and the pipeline as a whole is resolved by the same {@link PipelineResolver} {@code niem run
 * --pipeline} uses -- with anything not yet saved staged into a scratch directory so the resolver
 * sees exactly the files a save would write. ADR 0021's rule, extended: the designer cannot accept a
 * pipeline the command line would refuse.
 *
 * <h2>What preview may never do</h2>
 *
 * <p>Preview reads real records from a real origin, and the origin is often the one a live ingest is
 * reading. So it takes records the way nothing else in the platform does: on a consumer group that
 * exists only for this preview, reading at most a handful, and <strong>never calling
 * {@link SourceHandle#acknowledge()}</strong> -- no offset is committed, no remote file is archived or
 * deleted, no watermark moves. A file drop is read in place, as it always is. The records are mapped
 * in memory with a fresh cluster index and nothing is written anywhere: no bronze, no silver, no
 * projection, no exchange, no quarantine file.
 */
public final class PipelineDesigner {

    /** The most records a preview reads, whatever is asked for. A preview is a look, not a load. */
    static final int PREVIEW_CEILING = 50;

    /** Kafka settings a preview replaces; see {@link #forPreview}. */
    private static final String KAFKA = "kafka";

    private final MappingWorkspace workspace;
    private final List<Path> deploymentDirectories;
    private final Supplier<ConnectorRegistry> connectors;
    private final ProjectionRegistry projections;
    private final Supplier<ExchangeRegistry> exchanges;
    private final ObjectMapper json = new ObjectMapper();

    public PipelineDesigner(MappingWorkspace workspace, List<Path> deploymentDirectories) {
        // Discovered afresh each time they are used: connectors and exchange writers are configured
        // in place, and one shared instance configured for a preview would carry that configuration
        // into the next request.
        this(workspace, deploymentDirectories, ConnectorRegistry::discover,
                ProjectionRegistry.discover(), ExchangeRegistry::discover);
    }

    PipelineDesigner(
            MappingWorkspace workspace,
            List<Path> deploymentDirectories,
            Supplier<ConnectorRegistry> connectors,
            ProjectionRegistry projections,
            Supplier<ExchangeRegistry> exchanges) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.deploymentDirectories = List.copyOf(deploymentDirectories);
        this.connectors = Objects.requireNonNull(connectors, "connectors");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.exchanges = Objects.requireNonNull(exchanges, "exchanges");
    }

    private ArtifactCatalog catalog(List<Path> extra) {
        List<Path> directories = new ArrayList<>(deploymentDirectories);
        directories.addAll(extra);
        return ArtifactCatalog.of(workspace.moduleRoot(), directories);
    }

    // =================================================================================== palette

    /** Everything a pipeline can be built from, as the components describe themselves. */
    public ObjectNode palette() {
        ObjectNode node = json.createObjectNode();
        ArtifactCatalog catalog = catalog(List.of());

        ArrayNode origins = node.putArray("origins");
        ConnectorRegistry registry = connectors.get();
        for (ConnectorType type : registry.availableTypes()) {
            SourceConnector connector = registry.forType(type).orElseThrow();
            ObjectNode entry = origins.addObject();
            entry.put("type", type.id());
            entry.put("label", label(type.id()));
            entry.put("icon", icon(type.id()));
            entry.put("summary", connector.summary());
            entry.set("settings", settings(connector.settings()));
        }

        ArrayNode processors = node.putArray("processors");
        ObjectNode mapping = processors.addObject();
        mapping.put("type", "mapping");
        mapping.put("label", "Mapping");
        mapping.put("icon", "mapping");
        mapping.put("summary", "Contract gate in, field steps, identity resolution, contract gate out. "
                + "Versioned as one artifact.");
        ArrayNode available = mapping.putArray("mappings");
        for (MappingWorkspace.MappingFile file : workspace.mappings()) {
            if (!file.loadable()) {
                continue;
            }
            try {
                MappingDefinition definition = workspace.load(file.fileName());
                ObjectNode entry = available.addObject();
                entry.put("ref", definition.qualifiedName());
                entry.put("file", file.fileName());
                entry.put("sourceId", definition.sourceId());
                entry.put("hops", definition.hops().size());
                ArrayNode produces = entry.putArray("produces");
                definition.hops().forEach(hop -> produces.add(hop.identity().entityType()));
            } catch (RuntimeException unreadable) {
                // Listed as unloadable by /api/mappings already; the palette offers what can be used.
            }
        }

        ArrayNode destinations = node.putArray("destinations");
        for (ProjectionType type : projections.availableTypes()) {
            ProjectionFactory factory = projections.forType(type).orElseThrow();
            ObjectNode entry = destinations.addObject();
            entry.put("kind", "projection");
            entry.put("type", type.id());
            entry.put("label", label(type.id()));
            entry.put("icon", icon(type.id()));
            entry.put("summary", factory.summary());
            entry.set("settings", settings(factory.settings()));
        }
        ExchangeRegistry exchangeRegistry = exchanges.get();
        for (ExchangeType type : exchangeRegistry.availableTypes()) {
            ObjectNode entry = destinations.addObject();
            entry.put("kind", "exchange");
            entry.put("type", type.id());
            entry.put("label", label(type.id()));
            entry.put("icon", "repository");
            var writer = exchangeRegistry.forType(type).orElseThrow();
            entry.put("summary", writer.summary());
            entry.set("settings", settings(writer.settings()));
        }

        node.set("sources", entries(catalog, ArtifactCatalog.Kind.SOURCE));
        node.set("projections", entries(catalog, ArtifactCatalog.Kind.PROJECTION));
        node.set("exchanges", entries(catalog, ArtifactCatalog.Kind.EXCHANGE));
        return node;
    }

    private ArrayNode entries(ArtifactCatalog catalog, ArtifactCatalog.Kind kind) {
        ArrayNode list = json.createArrayNode();
        for (ArtifactCatalog.Entry entry : catalog.entries(kind)) {
            ObjectNode item = list.addObject();
            item.put("name", entry.name());
            item.put("version", entry.version());
            item.put("ref", entry.qualifiedName());
            item.put("file", entry.file().getFileName().toString());
            item.put("deployment", entry.deployment());
            try {
                switch (kind) {
                    case SOURCE -> {
                        SourceDefinition source = SourceDefinition.load(entry.file());
                        item.put("sourceId", source.sourceId());
                        item.put("instance", source.connectorInstanceId());
                        item.put("type", source.type().id());
                        item.set("settings", redacted(source.settings(),
                                describedSettings(source.type())));
                    }
                    case PROJECTION -> {
                        var projection = gov.niemplatform.projections.api.ProjectionDefinition.load(entry.file());
                        item.put("type", projection.type().id());
                        item.set("settings", json.valueToTree(projection.settings()));
                    }
                    case EXCHANGE -> {
                        ExchangeDefinition exchange = ExchangeDefinition.load(entry.file(), CoreCanonicalTypes.ALL);
                        item.put("type", exchange.type().id());
                        item.put("sourceId", exchange.sourceId());
                        item.put("root", exchange.assemble().rootType());
                        item.set("settings", json.valueToTree(exchange.settings()));
                    }
                    default -> { }
                }
            } catch (RuntimeException unreadable) {
                item.put("problem", String.valueOf(unreadable.getMessage()));
            }
        }
        return list;
    }

    /**
     * A source's settings with any literal secret replaced. A source definition written before the
     * designer may carry one (an SFTP password), and the browser has no business receiving it.
     */
    private ObjectNode redacted(Map<String, String> settings, List<SettingDescriptor> declared) {
        ObjectNode node = json.createObjectNode();
        settings.forEach((key, value) -> {
            boolean secret = declared.stream().anyMatch(d -> d.key().equals(key)
                    && d.sensitivity() == SettingDescriptor.Sensitivity.SECRET_VALUE);
            node.put(key, secret ? "••••••" : value);
        });
        return node;
    }

    private List<SettingDescriptor> describedSettings(ConnectorType type) {
        return connectors.get().forType(type).map(SourceConnector::settings).orElse(List.of());
    }

    private ArrayNode settings(List<SettingDescriptor> declared) {
        ArrayNode list = json.createArrayNode();
        for (SettingDescriptor setting : declared) {
            ObjectNode entry = list.addObject();
            entry.put("key", setting.key());
            entry.put("label", setting.label());
            entry.put("description", setting.description());
            entry.put("kind", setting.kind().name().toLowerCase(Locale.ROOT));
            entry.put("required", setting.required());
            if (setting.defaultValue() != null) {
                entry.put("default", setting.defaultValue());
            }
            ArrayNode options = entry.putArray("options");
            setting.options().forEach(options::add);
            entry.put("sensitivity", setting.sensitivity().name().toLowerCase(Locale.ROOT));
        }
        return list;
    }

    // ================================================================================= pipelines

    /** The pipelines this module carries. */
    public ObjectNode pipelines() {
        ObjectNode node = json.createObjectNode();
        ArrayNode list = node.putArray("pipelines");
        for (ArtifactCatalog.Entry entry : catalog(List.of()).entries(ArtifactCatalog.Kind.PIPELINE)) {
            if (entry.deployment()) {
                continue;
            }
            ObjectNode item = list.addObject();
            item.put("file", entry.file().getFileName().toString());
            item.put("name", entry.name());
            item.put("version", entry.version());
            try {
                PipelineDefinition.load(entry.file());
                item.put("loadable", true);
            } catch (PipelineDefinitionException e) {
                item.put("loadable", false);
                item.put("problem", e.getMessage());
            }
        }
        return node;
    }

    /**
     * One pipeline, as the draft the browser edits, with where the next save would go.
     *
     * <p>Every part is loaded as "existing": a pipeline on disk names artifacts, and opening it must
     * not turn them into copies.
     */
    public ObjectNode pipeline(String fileName) {
        Path file = inModule("pipelines", fileName);
        PipelineDefinition pipeline = PipelineDefinition.load(file);
        ObjectNode draft = json.createObjectNode();
        ObjectNode head = draft.putObject("pipeline");
        head.put("name", pipeline.name());
        head.put("version", workspace.nextVersion(pipeline.version()));
        head.put("description", pipeline.description());
        head.put("openedFrom", pipeline.qualifiedName());

        ObjectNode origin = draft.putObject("origin");
        origin.put("mode", "existing");
        origin.put("sourceId", pipeline.originSource());
        origin.put("instance", pipeline.originInstance());
        draft.put("mapping", pipeline.mapping());

        ArrayNode destinations = draft.putArray("destinations");
        pipeline.projections().forEach(name -> {
            ObjectNode entry = destinations.addObject();
            entry.put("kind", "projection");
            entry.put("mode", "existing");
            entry.put("ref", name);
        });
        pipeline.exchanges().forEach(name -> {
            ObjectNode entry = destinations.addObject();
            entry.put("kind", "exchange");
            entry.put("mode", "existing");
            entry.put("ref", name);
        });

        ObjectNode node = json.createObjectNode();
        node.set("draft", draft);
        node.put("file", fileName);
        node.put("yaml", read(file));
        node.set("validation", validate(draft, false));
        return node;
    }

    // ================================================================================ validation

    /** What a draft becomes: the pipeline, the files a save would write, and what is wrong. */
    private record Materialized(
            PipelineDefinition pipeline,
            List<PendingFile> pending,
            Map<String, List<String>> problems,
            List<String> notes,
            PipelineResolver.Resolution resolution) {}

    /** A file a save would write, relative to the module root. */
    private record PendingFile(String directory, String fileName, String yaml, String stage) {}

    /** Checks a draft the browser holds. */
    public ObjectNode validate(JsonNode draft, boolean forSave) {
        Materialized result = materialize(draft, forSave);
        return describe(result);
    }

    private ObjectNode describe(Materialized result) {
        ObjectNode node = json.createObjectNode();
        boolean valid = result.problems().values().stream().allMatch(List::isEmpty);
        node.put("valid", valid);
        ObjectNode problems = node.putObject("problems");
        result.problems().forEach((stage, list) -> {
            if (!list.isEmpty()) {
                ArrayNode entries = problems.putArray(stage);
                list.forEach(entries::add);
            }
        });
        ArrayNode notes = node.putArray("notes");
        result.notes().forEach(notes::add);
        ArrayNode writes = node.putArray("writes");
        result.pending().forEach(file -> writes.add(file.directory() + "/" + file.fileName()));
        if (result.pipeline() != null) {
            node.put("yaml", result.pipeline().toYaml(header()));
            node.put("qualifiedName", result.pipeline().qualifiedName());
        }
        if (result.resolution() != null) {
            result.resolution().mapping().ifPresent(mapping -> {
                ObjectNode m = node.putObject("mapping");
                m.put("sourceId", mapping.sourceId());
                m.put("hops", mapping.hops().size());
                ArrayNode produces = m.putArray("produces");
                mapping.hops().forEach(hop -> produces.add(hop.identity().entityType()));
            });
            result.resolution().source().ifPresent(source -> {
                ObjectNode o = node.putObject("origin");
                o.put("type", source.type().id());
                o.put("sourceId", source.sourceId());
                o.put("instance", source.connectorInstanceId());
                o.set("settings", redacted(source.settings(), describedSettings(source.type())));
            });
        }
        return node;
    }

    private Materialized materialize(JsonNode draft, boolean forSave) {
        Map<String, List<String>> problems = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        List<PendingFile> pending = new ArrayList<>();
        ArtifactCatalog existing = catalog(List.of());

        // --- the pipeline itself --------------------------------------------------------
        JsonNode head = draft.path("pipeline");
        String name = text(head, "name");
        String version = text(head, "version");
        String description = text(head, "description");
        if (name != null && version != null
                && !existing.find(ArtifactCatalog.Kind.PIPELINE, name + "@" + version).isEmpty()) {
            (forSave ? problems(problems, "pipeline") : notes).add(
                    name + "@" + version + " already exists; saving writes a new version, never over it"
                            + " (next: " + nextVersionOf(existing, name, version) + ")");
        }

        // --- origin ----------------------------------------------------------------------
        JsonNode origin = draft.path("origin");
        String sourceId = text(origin, "sourceId");
        String instance = text(origin, "instance");
        if ("new".equals(text(origin, "mode"))) {
            pending.addAll(newSource(origin, existing, problems(problems, "origin")));
        } else if (sourceId == null || instance == null) {
            problems(problems, "origin").add("choose an origin: an existing source definition, or a new one");
        }

        // --- mapping ---------------------------------------------------------------------
        String mapping = text(draft, "mapping");
        if (mapping == null) {
            problems(problems, "mapping").add("choose the mapping that turns this source into canonical records");
        }

        // --- destinations ----------------------------------------------------------------
        List<String> projectionRefs = new ArrayList<>();
        List<String> exchangeRefs = new ArrayList<>();
        for (JsonNode destination : draft.path("destinations")) {
            String kind = text(destination, "kind");
            boolean isNew = "new".equals(text(destination, "mode"));
            if ("exchange".equals(kind)) {
                if (isNew) {
                    problems(problems, "exchange:" + text(destination, "name")).add("an exchange's assembly "
                            + "is authored in its own file (ADR 0034); choose an existing exchange");
                    continue;
                }
                exchangeRefs.add(text(destination, "ref"));
            } else if (isNew) {
                PendingFile file = newProjection(destination, existing, problems);
                if (file != null) {
                    pending.add(file);
                }
                projectionRefs.add(text(destination, "name") + "@" + text(destination, "version"));
            } else {
                projectionRefs.add(text(destination, "ref"));
            }
        }
        projectionRefs.removeIf(Objects::isNull);
        exchangeRefs.removeIf(Objects::isNull);

        // --- the whole: parsed and resolved exactly as niem run would ----------------------
        PipelineDefinition pipeline = null;
        PipelineResolver.Resolution resolution = null;
        StringBuilder yaml = new StringBuilder();
        yaml.append("pipeline: ").append(name == null ? "" : name).append('\n');
        yaml.append("version: \"").append(version == null ? "" : version).append("\"\n");
        if (description != null) {
            yaml.append("description: ").append(quote(description)).append('\n');
        }
        yaml.append("origin:\n  source: ").append(nz(sourceId)).append("\n  instance: ").append(nz(instance)).append('\n');
        yaml.append("processors:\n  mapping: ").append(nz(mapping)).append('\n');
        yaml.append("destinations:\n  projections: ").append(list(projectionRefs))
                .append("\n  exchanges: ").append(list(exchangeRefs)).append('\n');
        try {
            pipeline = PipelineDefinition.parse(yaml.toString(), null);
        } catch (PipelineDefinitionException e) {
            // The same gap is often already reported more precisely by the stage's own check (a new
            // origin with no instance); saying it twice in two phrasings helps nobody.
            java.util.Set<String> alreadyReported = new java.util.HashSet<>();
            problems.forEach((stage, list) -> {
                if (!list.isEmpty()) {
                    alreadyReported.add(stage);
                }
            });
            e.problems().stream()
                    .filter(problem -> !alreadyReported.contains(stageOf(problem)))
                    .forEach(problem -> problems(problems, stageOf(problem)).add(problem));
        }

        if (pipeline != null) {
            Path staging = stage(pending);
            try {
                resolution = new PipelineResolver(catalog(staging == null ? List.of() : List.of(staging)),
                        connectors.get(), projections, exchanges.get())
                        .resolve(pipeline, PipelineResolver.Strictness.AUTHORING);
                resolution.problems().forEach(problem -> problems(problems, problem.stage()).add(problem.detail()));
                // Notes about the staging directory would read as "comes from the deployment" for
                // something the author is creating right now, which is not what it is.
                resolution.notes().stream()
                        .filter(note -> staging == null || !note.contains(staging.getFileName().toString()))
                        .filter(note -> !note.startsWith("origin: ") || !"new".equals(text(origin, "mode")))
                        .forEach(notes::add);
            } finally {
                delete(staging);
            }
        }
        return new Materialized(pipeline, pending, problems, notes, resolution);
    }

    private List<PendingFile> newSource(JsonNode origin, ArtifactCatalog existing, List<String> problems) {
        String sourceId = text(origin, "sourceId");
        String instance = text(origin, "instance");
        String type = text(origin, "type");
        Map<String, String> settings = stringMap(origin.path("settings"));
        if (sourceId == null || !sourceId.matches("[a-z][a-z0-9-]*")) {
            problems.add("'source id' is required, lower-case kebab-case: the source the records belong to");
        }
        if (instance == null || !instance.matches("[a-z][a-z0-9-]*")) {
            problems.add("'instance' is required, lower-case kebab-case: which transport of that source this is");
        }
        if (type == null) {
            problems.add("choose a transport");
            return List.of();
        }
        ConnectorRegistry registry = connectors.get();
        Optional<SourceConnector> connector = registry.forType(ConnectorType.of(type));
        if (connector.isEmpty()) {
            problems.add("transport '" + type + "' is not on this deployment's classpath");
            return List.of();
        }
        SettingsCheck.check(connector.get().settings(), settings)
                .forEach(problem -> problems.add(problem.toString()));
        if (problems.isEmpty()) {
            // The connector itself is the authority (ADR 0021): configuring checks what a form
            // cannot, such as SFTP's rule that some host-key check must be stated. configure() does
            // not connect anywhere.
            try (SourceConnector check = connector.get()) {
                check.useCheckpointStore(SourceCheckpointStore.inMemory());
                check.configure(new SourceDefinition(sourceId, instance, ConnectorType.of(type), settings,
                        duration(text(origin, "freshnessSla"), problems)).toConnectorConfig());
            } catch (ConnectorConfigurationException e) {
                e.problems().forEach(problem -> problems.add(problem.toString()));
            } catch (RuntimeException e) {
                problems.add(String.valueOf(e.getMessage()));
            }
        }
        if (sourceId != null && instance != null) {
            if (!existing.find(ArtifactCatalog.Kind.SOURCE, sourceId + "/" + instance).isEmpty()) {
                problems.add(sourceId + "/" + instance + " already exists; a changed transport is a new "
                        + "instance, so the one a running pipeline uses is never altered under it");
            }
            if (Files.exists(workspace.moduleRoot().resolve("sources").resolve(instance + ".yaml"))) {
                problems.add("sources/" + instance + ".yaml already exists");
            }
        }
        StringBuilder yaml = new StringBuilder(comment(header()));
        yaml.append("sourceId: ").append(nz(sourceId)).append('\n');
        yaml.append("connectorInstanceId: ").append(nz(instance)).append('\n');
        yaml.append("type: ").append(type).append('\n');
        String sla = text(origin, "freshnessSla");
        if (sla != null) {
            yaml.append("freshnessSla: ").append(sla).append('\n');
        }
        appendSettings(yaml, settings);
        return List.of(new PendingFile("sources", nz(instance) + ".yaml", yaml.toString(), "origin"));
    }

    private PendingFile newProjection(JsonNode destination, ArtifactCatalog existing, Map<String, List<String>> all) {
        String name = text(destination, "name");
        String version = text(destination, "version");
        String type = text(destination, "type");
        List<String> problems = problems(all, "projection:" + name);
        if (name == null || !name.matches("[a-z][a-z0-9-]*")) {
            problems.add("'name' is required, lower-case kebab-case");
        }
        if (version == null || !version.matches("\\d+\\.\\d+\\.\\d+")) {
            problems.add("'version' must be semver (MAJOR.MINOR.PATCH)");
        }
        if (type == null) {
            problems.add("choose a store");
            return null;
        }
        Optional<ProjectionFactory> factory = projections.forType(ProjectionType.of(type));
        if (factory.isEmpty()) {
            problems.add("store '" + type + "' is not on this deployment's classpath");
            return null;
        }
        Map<String, String> settings = stringMap(destination.path("settings"));
        SettingsCheck.check(factory.get().settings(), settings).forEach(p -> problems.add(p.toString()));
        if (name != null && version != null
                && !existing.find(ArtifactCatalog.Kind.PROJECTION, name + "@" + version).isEmpty()) {
            problems.add(name + "@" + version + " already exists; choose a new version");
        }
        StringBuilder yaml = new StringBuilder(comment(header()));
        yaml.append("projection: ").append(nz(name)).append('\n');
        yaml.append("version: \"").append(nz(version)).append("\"\n");
        yaml.append("type: ").append(type).append('\n');
        appendSettings(yaml, settings);
        return new PendingFile("projections", nz(name) + "-" + nz(version) + ".yaml", yaml.toString(),
                "projection:" + name);
    }

    // ===================================================================================== save

    /**
     * Writes a validated draft: the new source and projection definitions it created, then the
     * pipeline. Never over an existing file -- the check is made again at the moment of writing, not
     * only when the draft was validated.
     */
    public ObjectNode save(JsonNode draft) {
        Materialized result = materialize(draft, true);
        ObjectNode node = describe(result);
        if (!node.path("valid").asBoolean() || result.pipeline() == null) {
            node.put("saved", false);
            return node;
        }
        List<PendingFile> files = new ArrayList<>(result.pending());
        files.add(new PendingFile("pipelines", result.pipeline().fileName(),
                result.pipeline().toYaml(header()), "pipeline"));
        for (PendingFile file : files) {
            if (Files.exists(workspace.moduleRoot().resolve(file.directory()).resolve(file.fileName()))) {
                node.put("saved", false);
                node.putObject("problems").putArray(file.stage())
                        .add(file.directory() + "/" + file.fileName() + " appeared since this draft was "
                                + "checked; nothing was written");
                return node;
            }
        }
        ArrayNode written = node.putArray("written");
        for (PendingFile file : files) {
            Path target = workspace.moduleRoot().resolve(file.directory()).resolve(file.fileName());
            try {
                Files.createDirectories(target.getParent());
                // CREATE_NEW: if something raced in between the check above and here, the write
                // fails instead of replacing it.
                Files.writeString(target, file.yaml(), StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE_NEW);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot write " + target, e);
            }
            written.add(file.directory() + "/" + file.fileName());
        }
        node.put("saved", true);
        node.put("file", result.pipeline().fileName());
        return node;
    }

    // ============================================================================ test / preview

    /** Configures a fresh connector for the draft's origin and asks it whether it can reach its source. */
    public ObjectNode testSource(JsonNode draft) {
        ObjectNode node = json.createObjectNode();
        Optional<SourceDefinition> origin = originDefinition(draft.path("origin"), node);
        if (origin.isEmpty()) {
            return node;
        }
        SourceDefinition probe = forPreview(origin.get(), "probe", PREVIEW_CEILING);
        node.put("transport", probe.type().id());
        probe.settings().entrySet().stream()
                .filter(e -> e.getKey().equals("groupId"))
                .forEach(e -> node.put("consumerGroup", e.getValue()));
        try (SourceConnector connector = fresh(probe)) {
            HealthStatus health = connector.health();
            node.put("state", health.state().name());
            node.put("healthy", health.isHealthy());
            node.put("detail", health.detail() == null ? "reachable" : health.detail());
        } catch (ConnectorConfigurationException e) {
            node.put("state", HealthStatus.State.NOT_CONFIGURED.name());
            node.put("healthy", false);
            node.put("detail", String.join("; ", e.problems().stream().map(Object::toString).toList()));
        } catch (RuntimeException e) {
            node.put("state", HealthStatus.State.UNAVAILABLE.name());
            node.put("healthy", false);
            node.put("detail", String.valueOf(e.getMessage()));
        }
        return node;
    }

    /**
     * Reads a few records from the origin and shows what every stage would make of them, writing
     * nothing and acknowledging nothing. See the class comment.
     */
    public ObjectNode preview(JsonNode draft, int requested) {
        int limit = Math.max(1, Math.min(requested, PREVIEW_CEILING));
        if (text(draft, "mapping") == null) {
            // Before a mapping is chosen -- the wizard's "what does this source send?" step -- the
            // origin alone is read and shown raw.
            ObjectNode node = json.createObjectNode();
            node.put("writesNothing", true);
            node.put("originOnly", true);
            Optional<SourceDefinition> origin = originDefinition(draft.path("origin"), node);
            if (origin.isEmpty()) {
                node.put("ran", false);
                node.put("why", node.path("detail").asText("Choose an origin first."));
                return node;
            }
            return run(node, origin.get(), null, null, limit);
        }
        Materialized result = materialize(draft, false);
        ObjectNode node = describe(result);
        node.put("writesNothing", true);
        if (result.resolution() == null || result.resolution().source().isEmpty()
                || result.resolution().mapping().isEmpty()
                || !result.problems().getOrDefault("origin", List.of()).isEmpty()
                || !result.problems().getOrDefault("mapping", List.of()).isEmpty()) {
            // A new origin is not on disk; preview builds it from the draft instead.
            Optional<SourceDefinition> draftOrigin = "new".equals(text(draft.path("origin"), "mode"))
                    && result.problems().getOrDefault("origin", List.of()).isEmpty()
                    ? originDefinition(draft.path("origin"), json.createObjectNode()) : Optional.empty();
            if (draftOrigin.isEmpty() || result.resolution() == null || result.resolution().mapping().isEmpty()) {
                node.put("ran", false);
                node.put("why", "Preview needs an origin and a mapping that check out first.");
                return node;
            }
            return run(node, draftOrigin.get(), result.resolution().mapping().get(),
                    result.resolution(), limit);
        }
        return run(node, result.resolution().source().get(), result.resolution().mapping().get(),
                result.resolution(), limit);
    }

    /**
     * A few records read from an origin for someone to look at.
     *
     * @param why set when nothing could be read, saying what stopped it
     */
    record Sample(List<RawEnvelope> envelopes, String consumerGroup, String why) {

        boolean read() {
            return why == null;
        }
    }

    /**
     * Reads up to {@code limit} records from an origin, the way every look at an origin in the
     * designer does: on a throwaway consumer group where there is one, and closed without
     * acknowledging -- nothing is committed, archived, deleted or advanced. The pipeline preview, the
     * field preview and the skeleton a new mapping starts from all read through here, so there is
     * one place that has to be right about not touching a live feed.
     */
    Sample readSample(SourceDefinition origin, String purpose, int limit) {
        int bounded = Math.max(1, Math.min(limit, PREVIEW_CEILING));
        SourceDefinition preview = forPreview(origin, purpose, bounded);
        String consumerGroup = KAFKA.equals(preview.type().id()) ? preview.settings().get("groupId") : null;
        try (SourceConnector connector = fresh(preview)) {
            SourceHandle handle = connector.open();
            try (Stream<RawEnvelope> stream = handle.envelopes()) {
                return new Sample(stream.limit(bounded).toList(), consumerGroup, null);
            } finally {
                // Closed, never acknowledged.
                handle.close();
            }
        } catch (ConnectorConfigurationException e) {
            return new Sample(List.of(), consumerGroup,
                    String.join("; ", e.problems().stream().map(Object::toString).toList()));
        } catch (RuntimeException e) {
            return new Sample(List.of(), consumerGroup, "The origin could not be read: " + e.getMessage());
        }
    }

    /**
     * The origin a draft names, or -- when there is no draft, as when a mapping is opened on its own
     * -- the module's first source definition for the mapping's source.
     */
    Optional<SourceDefinition> originFor(JsonNode origin, String sourceId, ObjectNode report) {
        if (origin != null && origin.isObject() && text(origin, "sourceId") != null) {
            return originDefinition(origin, report);
        }
        if (sourceId == null) {
            return Optional.empty();
        }
        return catalog(List.of()).entries(ArtifactCatalog.Kind.SOURCE).stream()
                .filter(entry -> entry.name().startsWith(sourceId + "/"))
                .findFirst()
                .map(entry -> SourceDefinition.load(entry.file()));
    }

    MappingWorkspace workspace() {
        return workspace;
    }

    private ObjectNode run(ObjectNode node, SourceDefinition origin, MappingDefinition mapping,
            PipelineResolver.Resolution resolution, int limit) {
        Sample sample = readSample(origin, "preview", limit);
        if (sample.consumerGroup() != null) {
            node.put("consumerGroup", sample.consumerGroup());
        }
        if (!sample.read()) {
            node.put("ran", false);
            node.put("why", sample.why());
            return node;
        }
        List<RawEnvelope> envelopes = sample.envelopes();
        if (mapping == null) {
            ArrayNode raw = node.putObject("samples").putArray("origin");
            envelopes.forEach(envelope -> raw.add(truncate(envelope.payloadAsText(), 300)));
            node.putObject("stages").putObject("origin").put("out", envelopes.size());
            node.put("ran", true);
            node.put("read", envelopes.size());
            node.put("limit", limit);
            return node;
        }

        Map<String, HopContract> contracts = new LinkedHashMap<>();
        workspace.contracts().forEach(contract -> contracts.put(contract.hopId(), contract));
        Map<String, CanonicalTypeDescriptor> types = new LinkedHashMap<>();
        CoreCanonicalTypes.ALL.forEach(type -> types.put(type.name(), type));
        RecordingObservabilityEmitter recorder = new RecordingObservabilityEmitter();
        InMemoryClusterIndex index = new InMemoryClusterIndex(TenantId.of("designer-preview"));
        MappingPipeline pipeline = new MappingPipeline(mapping, contracts,
                Map.of(DeterministicResolutionProvider.PROVIDER_ID, new IndexedResolutionProvider(
                        new DeterministicResolutionProvider(index), index)),
                types, new QuarantineSink.InMemory(), recorder);

        List<Record> produced = new ArrayList<>();
        ArrayNode originSamples = json.createArrayNode();
        ArrayNode mapped = json.createArrayNode();
        ArrayNode held = json.createArrayNode();
        int heldAtGate = 0;
        int heldRecords = 0;
        for (int i = 0; i < envelopes.size(); i++) {
            RawEnvelope envelope = envelopes.get(i);
            String raw = truncate(envelope.payloadAsText(), 300);
            if (i < 20) {
                originSamples.add(raw);
            }
            int before = recorder.eventsOfType(ContractViolation.class).size();
            MappingPipeline.Outcome outcome = pipeline.process(envelope, "designer-preview");
            List<ContractViolation> violations = recorder.eventsOfType(ContractViolation.class)
                    .subList(before, recorder.eventsOfType(ContractViolation.class).size());
            produced.addAll(outcome.canonicalRecords());
            for (Record record : outcome.canonicalRecords()) {
                if (mapped.size() < 40) {
                    mapped.add(sample(record, i));
                }
            }
            if (!outcome.fullyMapped()) {
                heldRecords++;
                if (violations.stream().anyMatch(v -> v.direction() == Direction.INPUT)) {
                    heldAtGate++;
                }
                ObjectNode entry = held.addObject();
                entry.put("record", i + 1);
                entry.put("raw", raw);
                ArrayNode hops = entry.putArray("quarantinedHops");
                outcome.quarantinedHops().forEach(hops::add);
                ArrayNode skipped = entry.putArray("skippedHops");
                outcome.skippedHops().forEach(skipped::add);
                ArrayNode why = entry.putArray("violations");
                // Shapes, never values (ADR 0015): what the contract expected and what kind of value
                // arrived.
                violations.forEach(v -> why.add(v.summary()));
            }
        }

        ObjectNode stages = node.putObject("stages");
        stages.putObject("origin").put("out", envelopes.size());
        ObjectNode gate = stages.putObject("gate");
        gate.put("in", envelopes.size());
        gate.put("held", heldAtGate);
        gate.put("out", envelopes.size() - heldAtGate);
        ObjectNode mappingStage = stages.putObject("mapping");
        mappingStage.put("in", envelopes.size() - heldAtGate);
        mappingStage.put("held", heldRecords - heldAtGate);
        mappingStage.put("out", produced.size());
        ObjectNode byType = mappingStage.putObject("byType");
        produced.forEach(record -> byType.put(simple(record.typeName()),
                byType.path(simple(record.typeName())).asInt() + 1));

        ObjectNode destinations = stages.putObject("destinations");
        // Keyed by the name the pipeline uses, which is the id the canvas draws the stage under. A
        // projection a deployment supplies is still counted: every projection receives every
        // canonical record, so what it would get does not depend on where it lives.
        for (String ref : resolution.pipeline().projections()) {
            ObjectNode entry = destinations.putObject("projection:" + ref);
            entry.put("in", produced.size());
            entry.put("unit", "records");
        }
        for (String ref : resolution.pipeline().exchanges()) {
            String name = ref.contains("@") ? ref.substring(0, ref.lastIndexOf('@')) : ref;
            ObjectNode entry = destinations.putObject("exchange:" + ref);
            entry.put("unit", "documents");
            resolution.exchanges().stream()
                    .filter(exchange -> exchange.exchangeName().equals(name))
                    .findFirst()
                    .ifPresentOrElse(
                            // An exchange sends documents, not records: an incident with everyone on
                            // it is one document. Assembled here exactly as a run assembles them.
                            exchange -> entry.put("in", new DocumentAssembler(CoreCanonicalTypes.ALL)
                                    .assemble(exchange.assemble(), produced).documents().size()),
                            () -> entry.put("unresolved", true));
        }

        var account = pipeline.account();
        ObjectNode completeness = node.putObject("completeness");
        completeness.put("summary", account.summary());
        completeness.put("balanced", account.balances());
        completeness.put("landed", account.landedCount());
        completeness.put("produced", account.producedCount());
        completeness.put("quarantined", account.quarantinedCount());
        completeness.put("skipped", account.skippedCount());

        ObjectNode samples = node.putObject("samples");
        samples.set("origin", originSamples);
        samples.set("mapped", mapped);
        samples.set("held", held);
        node.put("ran", true);
        node.put("read", envelopes.size());
        node.put("limit", limit);
        return node;
    }

    /**
     * The origin as a preview or probe reads it: on its own consumer group, for a handful of records.
     *
     * <p>Kafka is the case that matters. The group is the read position; previewing on the live
     * ingest's group would take its records and, if anything committed, move its offsets. A group
     * named for this one preview has no committed position, so it starts where the source's own
     * {@code autoOffsetReset} says, and since nothing acknowledges, it commits none either.
     */
    static SourceDefinition forPreview(SourceDefinition origin, String purpose, int limit) {
        if (!KAFKA.equals(origin.type().id())) {
            return origin;
        }
        Map<String, String> settings = new LinkedHashMap<>(origin.settings());
        settings.put("groupId", "niem-designer-" + purpose + "-" + UUID.randomUUID().toString().substring(0, 8));
        settings.put("maxRecords", Integer.toString(limit));
        int idle = parse(settings.get("idleMillis"), 5_000);
        settings.put("idleMillis", Integer.toString(Math.min(idle, 3_000)));
        settings.put("pollMillis", "250");
        return new SourceDefinition(origin.sourceId(), origin.connectorInstanceId(), origin.type(),
                settings, origin.declaredFreshnessSla().orElse(null));
    }

    private SourceConnector fresh(SourceDefinition definition) {
        // A new registry, so a new instance nobody else has configured.
        SourceConnector connector = connectors.get().forType(definition.type()).orElseThrow(() ->
                new IllegalArgumentException("transport '" + definition.type() + "' is not on this "
                        + "deployment's classpath"));
        connector.useCheckpointStore(SourceCheckpointStore.inMemory());
        connector.configure(definition.toConnectorConfig());
        return connector;
    }

    private Optional<SourceDefinition> originDefinition(JsonNode origin, ObjectNode report) {
        String sourceId = text(origin, "sourceId");
        String instance = text(origin, "instance");
        if ("new".equals(text(origin, "mode"))) {
            String type = text(origin, "type");
            if (sourceId == null || instance == null || type == null) {
                report.put("state", "NOT_CONFIGURED");
                report.put("healthy", false);
                report.put("detail", "a new origin needs a source id, an instance and a transport");
                return Optional.empty();
            }
            List<String> problems = new ArrayList<>();
            var sla = duration(text(origin, "freshnessSla"), problems);
            return Optional.of(new SourceDefinition(sourceId, instance, ConnectorType.of(type),
                    stringMap(origin.path("settings")), sla));
        }
        List<ArtifactCatalog.Entry> found = catalog(List.of())
                .find(ArtifactCatalog.Kind.SOURCE, sourceId + "/" + instance);
        if (found.size() != 1) {
            report.put("state", "NOT_CONFIGURED");
            report.put("healthy", false);
            report.put("detail", "no single source definition " + sourceId + "/" + instance);
            return Optional.empty();
        }
        return Optional.of(SourceDefinition.load(found.get(0).file()));
    }

    // ================================================================================= helpers

    private ObjectNode sample(Record record, int index) {
        ObjectNode entry = json.createObjectNode();
        entry.put("record", index + 1);
        entry.put("type", simple(record.typeName()));
        ObjectNode fields = entry.putObject("fields");
        record.values().forEach((key, value) ->
                fields.put(key, value == null ? null : truncate(String.valueOf(value), 120)));
        return entry;
    }

    private static String simple(String typeName) {
        int hash = typeName.lastIndexOf('#');
        return hash < 0 ? typeName : typeName.substring(hash + 1);
    }

    private static String label(String typeId) {
        return switch (typeId) {
            case "file-drop" -> "File drop";
            case "kafka" -> "Kafka";
            case "sftp" -> "SFTP";
            case "ftps" -> "FTPS";
            case "ods" -> "ODS · PostgreSQL";
            case "search" -> "Search · Elasticsearch";
            case "graph" -> "Graph · Neo4j";
            case "cch-http" -> "Criminal history repository";
            default -> Character.toUpperCase(typeId.charAt(0)) + typeId.substring(1).replace('-', ' ');
        };
    }

    private static String icon(String typeId) {
        return switch (typeId) {
            case "file-drop" -> "folder";
            case "kafka" -> "stream";
            case "sftp", "ftps" -> "server";
            case "ods" -> "table";
            case "search" -> "search";
            case "graph" -> "graph";
            default -> "box";
        };
    }

    private String header() {
        return "Written by the pipeline designer (ADR 0037). Edit it here or in the designer; either is\n"
                + "checked by the same resolver `niem run --pipeline` uses.";
    }

    private static String comment(String header) {
        StringBuilder out = new StringBuilder();
        header.lines().forEach(line -> out.append("# ").append(line).append('\n'));
        return out.append('\n').toString();
    }

    private static void appendSettings(StringBuilder yaml, Map<String, String> settings) {
        if (settings.isEmpty()) {
            return;
        }
        yaml.append("\nsettings:\n");
        settings.forEach((key, value) -> yaml.append("  ").append(key).append(": ").append(quote(value)).append('\n'));
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    private static String list(List<String> names) {
        return names.isEmpty() ? "[]" : "[" + String.join(", ", names) + "]";
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private static Map<String, String> stringMap(JsonNode object) {
        Map<String, String> values = new LinkedHashMap<>();
        object.fields().forEachRemaining(entry -> {
            String value = entry.getValue().isNull() ? "" : entry.getValue().asText().trim();
            if (!value.isEmpty()) {
                values.put(entry.getKey(), value);
            }
        });
        return values;
    }

    private static java.time.Duration duration(String value, List<String> problems) {
        if (value == null) {
            return null;
        }
        try {
            return java.time.Duration.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            problems.add("'freshness SLA' must be an ISO-8601 duration such as PT15M");
            return null;
        }
    }

    private static int parse(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<String> problems(Map<String, List<String>> all, String stage) {
        return all.computeIfAbsent(stage, key -> new ArrayList<>());
    }

    private static String stageOf(String problem) {
        if (problem.contains("origin")) {
            return "origin";
        }
        if (problem.contains("processors")) {
            return "mapping";
        }
        if (problem.contains("destinations")) {
            return "destinations";
        }
        return "pipeline";
    }

    private String nextVersionOf(ArtifactCatalog catalog, String name, String version) {
        String next = workspace.nextVersion(version);
        while (!catalog.find(ArtifactCatalog.Kind.PIPELINE, name + "@" + next).isEmpty()) {
            next = workspace.nextVersion(next);
        }
        return next;
    }

    private Path stage(List<PendingFile> pending) {
        if (pending.isEmpty()) {
            return null;
        }
        try {
            Path staging = Files.createTempDirectory("niem-designer-");
            for (PendingFile file : pending) {
                Files.writeString(staging.resolve(file.directory() + "-" + file.fileName()), file.yaml(),
                        StandardCharsets.UTF_8);
            }
            return staging;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot stage a draft for validation", e);
        }
    }

    private static void delete(Path staging) {
        if (staging == null) {
            return;
        }
        try (var files = Files.list(staging)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(staging);
        } catch (IOException ignored) {
            // A temp directory left behind holds a draft of non-secret settings; not worth failing
            // the request over.
        }
    }

    private Path inModule(String directory, String fileName) {
        Path file = workspace.moduleRoot().resolve(directory).resolve(fileName).normalize();
        if (!file.startsWith(workspace.moduleRoot().resolve(directory))) {
            throw new IllegalArgumentException("'" + fileName + "' is outside the module");
        }
        return file;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
