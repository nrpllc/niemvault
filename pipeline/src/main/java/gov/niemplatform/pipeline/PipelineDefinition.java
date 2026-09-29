package gov.niemplatform.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * A pipeline, as a versioned artifact (ADR 0037): where records come from, what they become, and
 * where they go.
 *
 * <pre>{@code
 * pipeline: leon-cad-live
 * version: "1.0.0"
 * origin:
 *   source: leon-so-cad
 *   instance: leon-cad-kafka-1
 * processors:
 *   mapping: leon-cad-to-canonical@1.0.0
 * destinations:
 *   projections: [demo-ods, demo-search, demo-graph]
 *   exchanges: [fdle-cch-incidents-leon]
 * }</pre>
 *
 * <p><strong>Names, not paths, and not copies.</strong> Every part is already an artifact of its
 * own -- a source definition (ADR 0029), a mapping, a projection definition (ADR 0035), an exchange
 * (ADR 0034) -- with its own version and its own reviewers. A pipeline that embedded them would be
 * a second copy of each that could disagree with the first; one that named file paths would break
 * the moment a deployment kept its definitions somewhere else. So it names them, and
 * {@link PipelineResolver} finds them: in the module, and in whatever directories a deployment
 * supplies. That is also how a deployment says <em>where</em> -- the pipeline says a projection
 * named {@code demo-ods}, the deployment's own {@code demo-ods} says which database.
 *
 * <p>The processors are the mapping. Its contract gates and its identity resolution are part of
 * it, versioned with it, and there is nowhere here to declare a second set.
 *
 * <p>Strict on unrecognised keys (ADR 0010), everywhere in the document: a misspelled
 * {@code destinatons} that was ignored would run a pipeline that projects nowhere and says nothing.
 */
public record PipelineDefinition(
        String name,
        String version,
        String description,
        String originSource,
        String originInstance,
        String mapping,
        List<String> projections,
        List<String> exchanges) {

    private static final Set<String> ROOT_KEYS =
            Set.of("pipeline", "version", "description", "origin", "processors", "destinations");
    private static final Set<String> ORIGIN_KEYS = Set.of("source", "instance");
    private static final Set<String> PROCESSOR_KEYS = Set.of("mapping");
    private static final Set<String> DESTINATION_KEYS = Set.of("projections", "exchanges");

    public PipelineDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(originSource, "originSource");
        Objects.requireNonNull(originInstance, "originInstance");
        Objects.requireNonNull(mapping, "mapping");
        description = description == null ? "" : description;
        projections = List.copyOf(projections);
        exchanges = List.copyOf(exchanges);
    }

    /** "name@version". */
    public String qualifiedName() {
        return name + "@" + version;
    }

    /** The file name this pipeline is kept under in a module's {@code pipelines/} directory. */
    public String fileName() {
        return name + "-" + version + ".yaml";
    }

    public static PipelineDefinition load(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PipelineDefinitionException(file, List.of("cannot be read: " + e.getMessage()));
        }
        return parse(text, file);
    }

    /**
     * Reads a pipeline from text.
     *
     * @param where named in errors; may be null for a draft that has no file yet
     * @throws PipelineDefinitionException naming every problem at once
     */
    public static PipelineDefinition parse(String text, Path where) {
        Object parsed;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            parsed = new Yaml(new SafeConstructor(options)).load(text);
        } catch (RuntimeException e) {
            throw new PipelineDefinitionException(where, List.of("is not valid YAML: " + e.getMessage()));
        }
        if (!(parsed instanceof Map<?, ?> document)) {
            throw new PipelineDefinitionException(where, List.of("must be a YAML mapping at the top level"));
        }

        List<String> problems = new ArrayList<>();
        Map<String, Object> root = block(document, ROOT_KEYS, "", problems);

        String name = text(root.get("pipeline"));
        String version = text(root.get("version"));
        if (name == null) {
            problems.add("'pipeline' is required: the name this pipeline is known by");
        } else if (!name.matches("[a-z][a-z0-9-]*")) {
            problems.add("'pipeline' must be lower-case kebab-case, found '" + name + "'");
        }
        if (version == null) {
            problems.add("'version' is required");
        } else if (!version.matches("\\d+\\.\\d+\\.\\d+")) {
            problems.add("'version' must be semver (MAJOR.MINOR.PATCH), found '" + version + "'");
        }

        String source = null;
        String instance = null;
        Object origin = root.get("origin");
        if (origin instanceof Map<?, ?> originMap) {
            Map<String, Object> o = block(originMap, ORIGIN_KEYS, "origin.", problems);
            source = text(o.get("source"));
            instance = text(o.get("instance"));
            if (source == null) {
                problems.add("'origin.source' is required: the source id the records belong to");
            }
            if (instance == null) {
                problems.add("'origin.instance' is required: which of that source's transports -- "
                        + "a source may arrive by file drop and by Kafka, and they are not the same");
            }
        } else {
            problems.add("'origin' is required: a pipeline with no origin reads nothing");
        }

        String mapping = null;
        Object processors = root.get("processors");
        if (processors instanceof Map<?, ?> processorMap) {
            Map<String, Object> p = block(processorMap, PROCESSOR_KEYS, "processors.", problems);
            mapping = text(p.get("mapping"));
            if (mapping == null) {
                problems.add("'processors.mapping' is required, as name@version");
            } else if (!mapping.matches("[a-z][a-z0-9-]*@\\d+\\.\\d+\\.\\d+")) {
                problems.add("'processors.mapping' must be name@version, pinned, found '" + mapping
                        + "'. An unpinned mapping would change what this pipeline does with no new "
                        + "version of the pipeline to say so");
            }
        } else {
            problems.add("'processors' is required: it names the mapping, which carries the contract "
                    + "gates and identity resolution");
        }

        List<String> projections = List.of();
        List<String> exchanges = List.of();
        Object destinations = root.get("destinations");
        if (destinations instanceof Map<?, ?> destinationMap) {
            Map<String, Object> d = block(destinationMap, DESTINATION_KEYS, "destinations.", problems);
            projections = names(d.get("projections"), "destinations.projections", problems);
            exchanges = names(d.get("exchanges"), "destinations.exchanges", problems);
        } else if (destinations != null) {
            problems.add("'destinations' must be a mapping with 'projections' and/or 'exchanges'");
        }

        if (!problems.isEmpty()) {
            throw new PipelineDefinitionException(where, problems);
        }
        return new PipelineDefinition(name, version, text(root.get("description")),
                source, instance, mapping, projections, exchanges);
    }

    /**
     * The artifact text for this pipeline.
     *
     * <p>Written by the designer. Plain and ordered the same way every time, so two versions of a
     * pipeline diff to exactly what changed.
     */
    public String toYaml(String header) {
        StringBuilder out = new StringBuilder();
        if (header != null && !header.isBlank()) {
            header.lines().forEach(line -> out.append("# ").append(line).append('\n'));
            out.append('\n');
        }
        out.append("pipeline: ").append(name).append('\n');
        out.append("version: \"").append(version).append("\"\n");
        if (!description.isBlank()) {
            out.append("description: ").append(quote(description)).append('\n');
        }
        out.append("\norigin:\n");
        out.append("  source: ").append(originSource).append('\n');
        out.append("  instance: ").append(originInstance).append('\n');
        out.append("\nprocessors:\n");
        out.append("  mapping: ").append(mapping).append('\n');
        if (!projections.isEmpty() || !exchanges.isEmpty()) {
            out.append("\ndestinations:\n");
            if (!projections.isEmpty()) {
                out.append("  projections:\n");
                projections.forEach(p -> out.append("    - ").append(p).append('\n'));
            }
            if (!exchanges.isEmpty()) {
                out.append("  exchanges:\n");
                exchanges.forEach(e -> out.append("    - ").append(e).append('\n'));
            }
        }
        return out.toString();
    }

    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    private static Map<String, Object> block(
            Map<?, ?> source, Set<String> allowed, String at, List<String> problems) {
        Map<String, Object> known = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String k = String.valueOf(key);
            if (allowed.contains(k)) {
                known.put(k, value);
            } else {
                problems.add("unrecognised key '" + at + k + "'; accepted here: "
                        + allowed.stream().sorted().toList());
            }
        });
        return known;
    }

    private static List<String> names(Object declared, String at, List<String> problems) {
        if (declared == null) {
            return List.of();
        }
        if (!(declared instanceof List<?> list)) {
            problems.add("'" + at + "' must be a list of names");
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Object entry : list) {
            String name = text(entry);
            if (name == null || entry instanceof Map || entry instanceof List) {
                problems.add("'" + at + "' entries must be names");
            } else if (names.contains(name)) {
                problems.add("'" + at + "' names '" + name + "' twice");
            } else {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private static String text(Object value) {
        if (value == null || value instanceof Map || value instanceof List) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
