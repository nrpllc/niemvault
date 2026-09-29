package gov.niemplatform.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Every artifact a pipeline can name, found by what it declares itself to be (ADR 0037).
 *
 * <p>Two tiers. The module's own directories -- {@code sources/}, {@code mappings/},
 * {@code projections/}, {@code exchanges/} -- hold content every deployment shares. Directories a
 * deployment supplies hold what only it knows: which database, which broker, which endpoint. A
 * name found in a deployment directory is the one used, and the module's copy of that name is
 * shadowed, which is how one pipeline runs unchanged against a laptop's stores and an agency's.
 *
 * <p>Kind is read from each file's top-level keys rather than from where it sits, so a deployment
 * can keep all of its definitions in one directory. A file that will not parse is listed as
 * unreadable rather than thrown: it is reported only if something needed it.
 */
public final class ArtifactCatalog {

    /** What an artifact declares itself to be. */
    public enum Kind {
        SOURCE, MAPPING, PROJECTION, EXCHANGE, PIPELINE
    }

    /**
     * One artifact.
     *
     * @param name what it is referred to by: {@code sourceId/instance} for a source,
     *     {@code name} for everything else
     * @param version its declared version, or empty for a source (a source definition has none)
     * @param deployment whether it came from a directory the deployment supplied
     */
    public record Entry(Kind kind, String name, String version, Path file, boolean deployment) {

        public String qualifiedName() {
            return version.isEmpty() ? name : name + "@" + version;
        }
    }

    private final Path moduleRoot;
    private final List<Entry> entries;
    private final List<String> unreadable;

    private ArtifactCatalog(Path moduleRoot, List<Entry> entries, List<String> unreadable) {
        this.moduleRoot = moduleRoot;
        this.entries = List.copyOf(entries);
        this.unreadable = List.copyOf(unreadable);
    }

    public static ArtifactCatalog of(Path moduleRoot, List<Path> deploymentDirectories) {
        Objects.requireNonNull(moduleRoot, "moduleRoot");
        List<Entry> entries = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        for (String directory : List.of("sources", "mappings", "projections", "exchanges", "pipelines")) {
            scan(moduleRoot.resolve(directory), false, entries, unreadable);
        }
        for (Path directory : deploymentDirectories) {
            scan(directory, true, entries, unreadable);
        }
        return new ArtifactCatalog(moduleRoot.toAbsolutePath().normalize(), entries, unreadable);
    }

    public Path moduleRoot() {
        return moduleRoot;
    }

    public List<Entry> entries() {
        return entries;
    }

    public List<Entry> entries(Kind kind) {
        return entries.stream().filter(entry -> entry.kind() == kind)
                .sorted(Comparator.comparing(Entry::name).thenComparing(Entry::version))
                .toList();
    }

    /** Files that would not parse, with why. Reported only when a lookup comes up empty. */
    public List<String> unreadable() {
        return unreadable;
    }

    /**
     * The artifacts a reference resolves to: none, one, or -- a problem the caller reports -- more
     * than one.
     *
     * <p>{@code name@version} matches that version only. A bare name matches every version of it;
     * the resolver refuses a bare name that matches more than one, because picking the newest would
     * change what a pipeline does the day someone adds a version, with no new pipeline version to
     * say so. Deployment entries win over module entries of the same name.
     */
    public List<Entry> find(Kind kind, String reference) {
        String name = reference;
        String version = null;
        int at = reference.lastIndexOf('@');
        if (at > 0) {
            name = reference.substring(0, at);
            version = reference.substring(at + 1);
        }
        String wantedName = name;
        String wantedVersion = version;
        List<Entry> matches = entries.stream()
                .filter(entry -> entry.kind() == kind && entry.name().equals(wantedName))
                .filter(entry -> wantedVersion == null || entry.version().equals(wantedVersion))
                .toList();
        List<Entry> fromDeployment = matches.stream().filter(Entry::deployment).toList();
        return fromDeployment.isEmpty() ? matches : fromDeployment;
    }

    private static void scan(Path directory, boolean deployment, List<Entry> entries, List<String> unreadable) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yaml")
                            || path.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            unreadable.add(directory + ": cannot be listed: " + e.getMessage());
            return;
        }
        for (Path file : files) {
            try {
                Entry entry = identify(file, deployment);
                if (entry != null) {
                    entries.add(entry);
                }
            } catch (RuntimeException | IOException e) {
                unreadable.add(file.getFileName() + ": " + e.getMessage());
            }
        }
    }

    /** What a file is, from its own top-level keys, or null for something that is none of these. */
    private static Entry identify(Path file, boolean deployment) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object parsed = new Yaml(new SafeConstructor(options))
                .load(Files.readString(file, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> document)) {
            return null;
        }
        String version = text(document.get("version"));
        if (document.containsKey("pipeline")) {
            return new Entry(Kind.PIPELINE, text(document.get("pipeline")), nz(version), file, deployment);
        }
        if (document.containsKey("mapping") && document.containsKey("hops")) {
            return new Entry(Kind.MAPPING, text(document.get("mapping")), nz(version), file, deployment);
        }
        if (document.containsKey("projection")) {
            return new Entry(Kind.PROJECTION, text(document.get("projection")), nz(version), file, deployment);
        }
        if (document.containsKey("exchange")) {
            return new Entry(Kind.EXCHANGE, text(document.get("exchange")), nz(version), file, deployment);
        }
        if (document.containsKey("sourceId") && document.containsKey("connectorInstanceId")) {
            return new Entry(Kind.SOURCE,
                    text(document.get("sourceId")) + "/" + text(document.get("connectorInstanceId")),
                    "", file, deployment);
        }
        return null;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
