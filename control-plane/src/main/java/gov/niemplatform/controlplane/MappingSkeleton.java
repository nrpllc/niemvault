package gov.niemplatform.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.niemplatform.connectors.api.SourceDefinition;
import gov.niemplatform.controlplane.advice.MappingAdvisor;
import gov.niemplatform.observability.ValueShape;
import gov.niemplatform.runtime.engine.HopDefinition;
import gov.niemplatform.runtime.engine.IdentitySpec;
import gov.niemplatform.runtime.engine.MappingDefinition;
import gov.niemplatform.runtime.transforms.TransformFactory;
import gov.niemplatform.storage.api.RawEnvelope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A first mapping for a source nothing reads yet, started from what the source actually sent.
 *
 * <p>Adding a data source used to stop at "No mapping in this module reads this source yet. Write
 * one in the Flow view" -- with nothing in the Flow view able to start one, and the columns the
 * wizard had just sampled forgotten. This closes that gap with the tools the platform already has,
 * rather than a second set:
 *
 * <ul>
 *   <li><strong>Columns</strong> are the sampled header, when the origin declares one, and
 *       {@code column1..N} otherwise.
 *   <li><strong>Hops</strong> follow an existing mapping in the module -- the same canonical types,
 *       dependencies, roles and identity rules -- because what a CAD export becomes is a modelling
 *       decision already made once, and a second, differently-shaped mapping for the same kind of
 *       feed is how two agencies' records stop joining up. Their steps start empty.
 *   <li><strong>Steps</strong> are seeded only from the module's own advisor (ADR 0023) at high
 *       confidence, with its rationale kept, so the author starts from proposals they can see the
 *       reasons for rather than from a guess.
 *   <li><strong>Contracts</strong>, one per hop, declare every sampled column. A column gets a
 *       pattern only when every sampled value has the same {@link ValueShape} -- a rule the sample
 *       itself would break is not a draft anyone wants -- and the shapes seen are reported either way,
 *       so an author can see why a column was left open.
 * </ul>
 *
 * <p>The contracts are written at once, as new files and never over an existing one: a contract has
 * no draft state (the editor writes contract edits straight to disk), and a mapping cannot be
 * validated, traced or edited against a contract that is not there. The mapping is <em>not</em>
 * written. It comes back as text for the field editor, and is saved like any other mapping, as a new
 * version, when its author says so.
 */
final class MappingSkeleton {

    /**
     * Seeded only at or above this. Below it the advisor's proposals stay proposals, in the editor's
     * Suggestions panel with their reasons, for a person to accept (ADR 0023): at 50% a name match
     * copied LastName into givenName, and a seeded step is an accepted one.
     */
    static final double SEED_CONFIDENCE = 0.6;

    private final PipelineDesigner designer;
    private final MappingAdvisor advisor;
    private final ObjectMapper json = new ObjectMapper();

    MappingSkeleton(PipelineDesigner designer, MappingAdvisor advisor) {
        this.designer = designer;
        this.advisor = advisor;
    }

    /**
     * @param request {@code origin}: the wizard's origin draft; {@code template}: an existing mapping
     *     to follow, {@code name@version} or file, optional; {@code limit}: records to sample
     */
    ObjectNode start(JsonNode request) {
        ObjectNode node = json.createObjectNode();
        MappingWorkspace workspace = designer.workspace();

        Optional<SourceDefinition> found = designer.originFor(request.get("origin"), null, node);
        if (found.isEmpty()) {
            return refused(node, "Configure and test the origin first: there is nothing to sample.");
        }
        SourceDefinition origin = found.get();
        String sourceId = origin.sourceId();
        if (workspace.mappings().stream().filter(MappingWorkspace.MappingFile::loadable)
                .map(file -> workspace.load(file.fileName()))
                .anyMatch(mapping -> mapping.sourceId().equals(sourceId))) {
            return refused(node, "A mapping for '" + sourceId + "' already exists; choose it instead of "
                    + "starting another.");
        }

        Optional<MappingDefinition> template = template(workspace, request.path("template").asText(null));
        if (template.isEmpty()) {
            return refused(node, "This module has no mapping to follow for the shape of a new one.");
        }

        // The header, when the origin skips one, is the first line the transport would have skipped.
        int skip = parseInt(origin.settings().get("skipHeaderLines"), 0);
        SourceDefinition withHeader = skip > 0 ? withSetting(origin, "skipHeaderLines", "0") : origin;
        int limit = Math.max(3, request.path("limit").asInt(20));
        PipelineDesigner.Sample sample = designer.readSample(withHeader, "skeleton", limit + skip);
        if (!sample.read()) {
            return refused(node, sample.why());
        }
        List<List<String>> rows = sample.envelopes().stream()
                .map(RawEnvelope::payloadAsText)
                .map(MappingSkeleton::split)
                .toList();
        if (rows.size() <= skip) {
            return refused(node, "The origin sent nothing to learn columns from.");
        }
        List<String> columns = columns(skip > 0 ? rows.get(skip - 1) : null, rows.get(skip).size());
        List<List<String>> data = rows.subList(skip, rows.size());
        Map<String, Map<String, Integer>> shapes = shapes(columns, data);

        String slug = sourceId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        String mappingName = slug + "-to-canonical";
        String emits = "source:" + slug + "/records";

        // Contracts first: validation, tracing and the column inspector all read them from disk.
        ArrayNode contractsWritten = node.putArray("contractsWritten");
        ArrayNode contractsKept = node.putArray("contractsKept");
        Map<String, String> hopIds = new LinkedHashMap<>();
        Map<String, String> contractNames = new LinkedHashMap<>();
        for (HopDefinition hop : template.get().hops()) {
            String hopId = hop.hopId() + "-" + slug;
            String contract = slug + "-" + hop.hopId().replaceFirst("^map-", "");
            hopIds.put(hop.hopId(), hopId);
            contractNames.put(hop.hopId(), contract);
            Path file = workspace.moduleRoot().resolve("contracts").resolve(contract + "-1.0.0.yaml");
            if (Files.exists(file)) {
                contractsKept.add("contracts/" + file.getFileName());
                continue;
            }
            write(file, contractYaml(contract, hopId, emits, hop.identity().entityType(), columns, shapes,
                    data.size(), sourceId));
            contractsWritten.add("contracts/" + file.getFileName());
        }

        // Steps: the advisor's confident proposals, per hop, as the Suggestions panel would make them.
        Map<String, List<MappingAdvisor.Suggestion>> seeded = new LinkedHashMap<>();
        Map<String, ValueShape> columnShapes = new LinkedHashMap<>();
        // The advisor is given each column's dominant shape: enough for it to propose a date pattern
        // the sample mostly follows, so the record that does not follow it shows up failing in the
        // field preview -- where the author can see it -- instead of the proposal being withheld.
        shapes.forEach((column, seen) -> dominant(seen).ifPresent(shape ->
                columnShapes.put(column, new ValueShape("String", null, shape))));
        double seedAt = request.path("seedConfidence").asDouble(SEED_CONFIDENCE);
        for (HopDefinition hop : template.get().hops()) {
            var target = ArtifactTypes.byName(hop.identity().entityType());
            List<MappingAdvisor.Suggestion> accepted = new ArrayList<>();
            if (target.isPresent()) {
                Set<String> written = new LinkedHashSet<>(hop.roles().keySet());
                // One proposal per column: the advisor ranks every field against every column, and a
                // column copied into two fields is nearly always its second-best guess being wrong.
                Set<String> read = new LinkedHashSet<>();
                advisor.suggest(new MappingAdvisor.Context(columns, columnShapes, target.get(), List.of(),
                                List.copyOf(TransformFactory.TYPES))).stream()
                        .filter(suggestion -> suggestion.confidence() >= seedAt)
                        .filter(MappingSkeleton::compiles)
                        .sorted(Comparator.comparingDouble(MappingAdvisor.Suggestion::confidence).reversed())
                        .filter(suggestion -> !written.contains(suggestion.target())
                                && suggestion.from().stream().noneMatch(read::contains))
                        .peek(suggestion -> {
                            written.add(suggestion.target());
                            read.addAll(suggestion.from());
                        })
                        .forEach(accepted::add);
            }
            seeded.put(hop.hopId(), accepted);
        }

        String yaml = mappingYaml(mappingName, sourceId, emits, columns, template.get(), hopIds,
                contractNames, seeded, slug, shapes);
        MappingWorkspace.ValidationReport report = workspace.validate(yaml);

        node.put("ran", true);
        node.put("yaml", yaml);
        node.put("mapping", mappingName + "@1.0.0");
        node.put("template", template.get().qualifiedName());
        node.put("header", skip > 0);
        node.put("sampled", data.size());
        ArrayNode columnList = node.putArray("columns");
        columns.forEach(column -> {
            ObjectNode entry = columnList.addObject();
            entry.put("name", column);
            ObjectNode seen = entry.putObject("shapes");
            shapes.get(column).forEach(seen::put);
        });
        ArrayNode seededList = node.putArray("seeded");
        seeded.forEach((templateHop, suggestions) -> suggestions.forEach(suggestion -> {
            ObjectNode entry = seededList.addObject();
            entry.put("hop", hopIds.get(templateHop));
            entry.put("target", suggestion.target());
            entry.put("type", suggestion.transformType());
            entry.put("confidence", suggestion.confidence());
        }));
        node.put("valid", report.valid());
        ArrayNode problems = node.putArray("problems");
        report.problems().forEach(problems::add);
        return node;
    }

    // ------------------------------------------------------------------------------ the sample

    /** Splits one delimited line, honouring double quotes the way the CSV decoder does. */
    static List<String> split(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString().trim());
                field.setLength(0);
            } else if (c != '\r' && c != '\n') {
                field.append(c);
            }
        }
        fields.add(field.toString().trim());
        return fields;
    }

    /** Header names made safe to use as column names, or {@code column1..N} without a header. */
    static List<String> columns(List<String> header, int width) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < width; i++) {
            String raw = header != null && i < header.size() ? header.get(i) : "";
            String name = raw.replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("^_+|_+$", "");
            if (name.isEmpty() || Character.isDigit(name.charAt(0)) || !seen.add(name)) {
                name = "column" + (i + 1);
                seen.add(name);
            }
            names.add(name);
        }
        return names;
    }

    /** Every shape seen per column, with how often. Shapes, never values (ADR 0015). */
    static Map<String, Map<String, Integer>> shapes(List<String> columns, List<List<String>> rows) {
        Map<String, Map<String, Integer>> shapes = new LinkedHashMap<>();
        for (int c = 0; c < columns.size(); c++) {
            Map<String, Integer> seen = new LinkedHashMap<>();
            for (List<String> row : rows) {
                String value = c < row.size() ? row.get(c) : "";
                if (!value.isBlank()) {
                    seen.merge(ValueShape.of(value).pattern(), 1, Integer::sum);
                }
            }
            shapes.put(columns.get(c), seen);
        }
        return shapes;
    }

    /**
     * A contract pattern for a column, when every sampled value has the same shape and the shape is
     * a format rather than text -- digits and punctuation, no letters, not truncated. A name has a
     * shape too, and a pattern drafted from five surnames would quarantine the sixth.
     */
    static Optional<String> patternFor(Map<String, Integer> seen) {
        if (seen.size() != 1) {
            return Optional.empty();
        }
        String shape = seen.keySet().iterator().next();
        if (shape == null || shape.indexOf('A') >= 0 || shape.indexOf('…') >= 0 || shape.indexOf('#') < 0) {
            return Optional.empty();
        }
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < shape.length(); ) {
            char c = shape.charAt(i);
            if (c == '#') {
                int run = 0;
                while (i < shape.length() && shape.charAt(i) == '#') {
                    run++;
                    i++;
                }
                regex.append("\\d{").append(run).append('}');
                continue;
            }
            if (c == '_') {
                regex.append(' ');
            } else if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
            i++;
        }
        return Optional.of(regex.append('$').toString());
    }

    // ------------------------------------------------------------------------------ the artifacts

    private static String contractYaml(String name, String hopId, String emits, String canonicalType,
            List<String> columns, Map<String, Map<String, Integer>> shapes, int sampled, String sourceId) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("# Drafted by the pipeline designer from ").append(sampled).append(" sampled record(s) of ")
                .append(sourceId).append(".\n")
                .append("# Every column the source sent is declared, none required. A pattern was drafted only\n")
                .append("# where every sampled value had the same shape; review each before this gates a live feed.\n")
                .append("contract: ").append(name).append('\n')
                .append("version: \"1.0.0\"\n")
                .append("hop: ").append(hopId).append('\n')
                .append("expects:\n")
                .append("  schema: \"").append(emits).append("\"\n")
                .append("  version: \"1.0.0\"\n")
                .append("  fields:\n");
        for (String column : columns) {
            yaml.append("    - name: ").append(column).append('\n')
                    .append("      type: string\n");
            patternFor(shapes.get(column)).ifPresent(pattern ->
                    yaml.append("      pattern: \"").append(pattern.replace("\\", "\\\\")).append("\"\n"));
        }
        yaml.append("emits:\n")
                .append("  canonicalType: ").append(canonicalType).append('\n');
        return yaml.toString();
    }

    private static String mappingYaml(String name, String sourceId, String emits, List<String> columns,
            MappingDefinition template, Map<String, String> hopIds, Map<String, String> contracts,
            Map<String, List<MappingAdvisor.Suggestion>> seeded, String slug,
            Map<String, Map<String, Integer>> shapes) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("# Started by the pipeline designer for ").append(sourceId).append(", following the hops of\n")
                .append("# ").append(template.qualifiedName()).append(". Steps marked \"advisor\" were proposed by the\n")
                .append("# module's advisor from column names and sampled shapes; check each before saving.\n")
                .append("mapping: ").append(name).append('\n')
                .append("version: \"1.0.0\"\n")
                .append("source: ").append(sourceId).append('\n')
                .append("decode:\n")
                .append("  format: delimited\n")
                .append("  emits: \"").append(emits).append("\"\n")
                .append("  columns:\n");
        for (String column : columns) {
            yaml.append("    - name: ").append(column).append('\n');
            Map<String, Integer> seen = shapes.get(column);
            if (!seen.isEmpty()) {
                yaml.append("      doc: \"Sampled shape(s): ").append(describe(seen)).append(". Say what it means.\"\n");
            }
        }
        yaml.append("hops:\n");
        for (HopDefinition hop : template.hops()) {
            yaml.append("  - id: ").append(hopIds.get(hop.hopId())).append('\n')
                    .append("    contract: ").append(contracts.get(hop.hopId())).append('\n')
                    .append("    version: \"1.0.0\"\n");
            if (!hop.dependsOn().isEmpty()) {
                yaml.append("    dependsOn: [").append(String.join(", ",
                        hop.dependsOn().stream().map(hopIds::get).toList())).append("]\n");
            }
            if (!hop.roles().isEmpty()) {
                yaml.append("    roles:\n");
                hop.roles().forEach((role, from) ->
                        yaml.append("      ").append(role).append(": ").append(hopIds.get(from)).append('\n'));
            }
            List<MappingAdvisor.Suggestion> steps = seeded.getOrDefault(hop.hopId(), List.of());
            if (steps.isEmpty()) {
                // Bare, not "[]": a step added in the editor is written as a list item under this
                // key, and under an inline empty list it would not be YAML.
                yaml.append("    steps:\n");
            } else {
                yaml.append("    steps:\n");
                for (MappingAdvisor.Suggestion step : steps) {
                    yaml.append("      # advisor, ").append(Math.round(step.confidence() * 100)).append("%: ")
                            .append(step.rationale().replace('\n', ' ')).append('\n')
                            .append("      - target: ").append(step.target()).append('\n')
                            .append("        type: ").append(step.transformType()).append('\n')
                            .append("        from: [").append(String.join(", ", step.from())).append("]\n");
                    if (!step.options().isEmpty()) {
                        yaml.append("        options:\n");
                        step.options().forEach((key, value) -> yaml.append("          ").append(key)
                                .append(": \"").append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n"));
                    }
                }
            }
            yaml.append(identityYaml(hop.identity(), hopIds, slug));
        }
        return yaml.toString();
    }

    private static String identityYaml(IdentitySpec identity, Map<String, String> hopIds, String slug) {
        StringBuilder yaml = new StringBuilder("    identity:\n");
        yaml.append("      mode: ").append(identity.mode().name().toLowerCase(Locale.ROOT)).append('\n')
                .append("      entityType: ").append(identity.entityType()).append('\n');
        if (identity.providerId() != null) {
            yaml.append("      provider: ").append(identity.providerId()).append('\n');
        }
        if (!identity.deriveFrom().isEmpty()) {
            yaml.append("      deriveFrom: [").append(String.join(", ", identity.deriveFrom().stream()
                    .map(field -> {
                        int dot = field.indexOf('.');
                        String hop = dot < 0 ? null : field.substring(0, dot);
                        String mapped = hop == null ? field : hopIds.getOrDefault(hop, hop) + field.substring(dot);
                        return "\"" + mapped + "\"";
                    }).toList())).append("]\n");
        }
        if (identity.prefix() != null && !identity.prefix().isEmpty()) {
            // An identity names the agency it came from, so two agencies' incident numbers can never
            // collide: INC/LEON-SO/ for the template becomes INC/<THIS SOURCE>/ here.
            String[] parts = identity.prefix().split("/");
            String prefix = parts.length >= 2
                    ? parts[0] + "/" + slug.toUpperCase(Locale.ROOT) + "/"
                    : identity.prefix();
            yaml.append("      prefix: \"").append(prefix).append("\"\n");
        }
        if (!identity.attributes().isEmpty()) {
            yaml.append("      attributes:\n");
            identity.attributes().forEach((key, field) ->
                    yaml.append("        ").append(key).append(": ").append(field).append('\n'));
        }
        return yaml.toString();
    }

    /** The shape at least three in five sampled values share, if there is one. */
    static Optional<String> dominant(Map<String, Integer> seen) {
        int total = seen.values().stream().mapToInt(Integer::intValue).sum();
        return seen.entrySet().stream()
                .filter(entry -> entry.getValue() * 5 >= total * 3)
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** A proposal the runtime would refuse is not a step anyone should start from. */
    private static boolean compiles(MappingAdvisor.Suggestion suggestion) {
        try {
            TransformFactory.create(new gov.niemplatform.runtime.transforms.TransformSpec(
                    suggestion.target(), suggestion.transformType(), suggestion.from(), suggestion.options()));
            return true;
        } catch (IllegalArgumentException refused) {
            return false;
        }
    }

    private static String describe(Map<String, Integer> seen) {
        return String.join(", ", seen.entrySet().stream()
                .map(entry -> entry.getKey() + (seen.size() > 1 ? " ×" + entry.getValue() : ""))
                .toList());
    }

    private static Optional<MappingDefinition> template(MappingWorkspace workspace, String reference) {
        List<MappingWorkspace.MappingFile> loadable = workspace.mappings().stream()
                .filter(MappingWorkspace.MappingFile::loadable)
                .toList();
        Optional<MappingWorkspace.MappingFile> chosen = reference == null ? Optional.empty()
                : loadable.stream().filter(file -> file.qualifiedName().equals(reference)
                        || file.fileName().equals(reference)).findFirst();
        return chosen.or(() -> loadable.stream().max(Comparator.comparingInt(
                        (MappingWorkspace.MappingFile file) -> workspace.load(file.fileName()).hops().size())
                .thenComparing(MappingWorkspace.MappingFile::fileName)))
                .map(file -> workspace.load(file.fileName()));
    }

    private static SourceDefinition withSetting(SourceDefinition origin, String key, String value) {
        Map<String, String> settings = new LinkedHashMap<>(origin.settings());
        settings.put(key, value);
        return new SourceDefinition(origin.sourceId(), origin.connectorInstanceId(), origin.type(),
                settings, origin.declaredFreshnessSla().orElse(null));
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private ObjectNode refused(ObjectNode node, String why) {
        node.put("ran", false);
        node.put("why", why);
        return node;
    }
}
