package gov.niemplatform.content;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads a domain module's manifest and enforces its compatibility range (spec §7).
 *
 * <p>The enforcement is the point. A declared range that nothing checks is documentation, and §7
 * says ranges are "declared explicitly in content metadata <strong>and enforced at load</strong>".
 * Content the running platform cannot honour is refused before any of it is read, so an operator
 * gets one clear answer rather than a mapping error three files later.
 *
 * <p>Strict about keys, like every other artifact loader here (ADR 0010): a misspelled
 * {@code platfrom} that silently left content unbounded would defeat the whole check.
 */
public final class ModuleManifestLoader {

    /** The file every domain module ships at its content root. */
    public static final String MANIFEST_FILE = "module.yaml";

    private static final Set<String> ROOT_KEYS = Set.of(
            "module", "version", "displayName", "description",
            "requires", "canonicalNamespaces", "steward");
    private static final Set<String> REQUIRES_KEYS = Set.of("platform", "canonicalModel");
    private static final Set<String> PLATFORM_KEYS = Set.of("minimum", "below");

    private final List<String> problems = new ArrayList<>();

    /** Loads the manifest from a module's content root. */
    public ModuleManifest load(Path moduleRoot) {
        Path manifest = moduleRoot.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifest)) {
            throw new ContentCompatibilityException(List.of(
                    "no " + MANIFEST_FILE + " at " + moduleRoot
                            + "; every domain module declares its compatibility range (spec section 7)"));
        }
        try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            return parse(reader);
        } catch (IOException e) {
            throw new ContentCompatibilityException(List.of(
                    "cannot read " + manifest + ": " + e.getMessage()));
        }
    }

    /** Loads a manifest from a stream, e.g. a module packaged on the classpath. */
    public ModuleManifest load(InputStream input) {
        return parse(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    /**
     * Loads a manifest and refuses it if the running platform is outside its declared range.
     *
     * @throws ContentCompatibilityException naming what to do about it, not merely that it failed
     */
    public ModuleManifest loadFor(Path moduleRoot, SemanticVersion platformVersion) {
        ModuleManifest manifest = load(moduleRoot);
        if (!manifest.supports(platformVersion)) {
            throw new ContentCompatibilityException(List.of(
                    "module '%s' declares platform %s: %s".formatted(
                            manifest.qualifiedName(),
                            manifest.platformVersions(),
                            manifest.platformVersions().explain(platformVersion))));
        }
        return manifest;
    }

    private ModuleManifest parse(Reader reader) {
        problems.clear();

        Map<String, Object> root;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object loaded = new Yaml(new SafeConstructor(options)).load(reader);
            if (!(loaded instanceof Map<?, ?> map)) {
                throw new ContentCompatibilityException(
                        List.of("expected a mapping at the root of " + MANIFEST_FILE));
            }
            root = cast(map);
        } catch (ContentCompatibilityException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ContentCompatibilityException(
                    List.of(MANIFEST_FILE + " is malformed: " + e.getMessage()));
        }

        rejectUnknownKeys("<root>", root.keySet(), ROOT_KEYS);

        String name = requireString("<root>", root, "module");
        String version = requireString("<root>", root, "version");
        VersionRange platform = parsePlatformRange(root.get("requires"));
        String canonicalModel = canonicalModelVersion(root.get("requires"));

        if (!problems.isEmpty()) {
            throw new ContentCompatibilityException(problems);
        }
        try {
            return new ModuleManifest(
                    name,
                    SemanticVersion.parse(version),
                    optionalString(root, "displayName") == null ? name : optionalString(root, "displayName"),
                    optionalString(root, "description"),
                    platform,
                    SemanticVersion.parse(canonicalModel),
                    stringList("<root>", root, "canonicalNamespaces"),
                    optionalString(root, "steward"));
        } catch (IllegalArgumentException e) {
            throw new ContentCompatibilityException(List.of(e.getMessage()));
        }
    }

    private VersionRange parsePlatformRange(Object requires) {
        if (!(requires instanceof Map<?, ?> map)) {
            problems.add("'requires' is missing; a module must declare the platform range it "
                    + "supports (spec section 7)");
            return null;
        }
        Map<String, Object> block = cast(map);
        rejectUnknownKeys("requires", block.keySet(), REQUIRES_KEYS);

        if (!(block.get("platform") instanceof Map<?, ?> platformMap)) {
            problems.add("'requires.platform' is missing");
            return null;
        }
        Map<String, Object> platform = cast(platformMap);
        rejectUnknownKeys("requires.platform", platform.keySet(), PLATFORM_KEYS);

        String minimum = requireString("requires.platform", platform, "minimum");
        String below = optionalString(platform, "below");

        if (minimum == null || !SemanticVersion.isValid(minimum)) {
            if (minimum != null) {
                problems.add("'requires.platform.minimum' must be semver, found '" + minimum + "'");
            }
            return null;
        }
        if (below != null && !SemanticVersion.isValid(below)) {
            problems.add("'requires.platform.below' must be semver, found '" + below + "'");
            return null;
        }
        try {
            return below == null ? VersionRange.from(minimum) : VersionRange.between(minimum, below);
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
            return null;
        }
    }

    private String canonicalModelVersion(Object requires) {
        if (!(requires instanceof Map<?, ?> map)) {
            return null;
        }
        String value = optionalString(cast(map), "canonicalModel");
        if (value == null) {
            problems.add("'requires.canonicalModel' is missing; a module declares the canonical "
                    + "model version its mappings were authored against");
        } else if (!SemanticVersion.isValid(value)) {
            problems.add("'requires.canonicalModel' must be semver, found '" + value + "'");
            return null;
        }
        return value;
    }

    // --- primitives ------------------------------------------------------

    private void rejectUnknownKeys(String location, Set<String> present, Set<String> allowed) {
        Set<String> unknown = new LinkedHashSet<>(present);
        unknown.removeAll(allowed);
        unknown.forEach(key -> problems.add(
                location + ": unrecognised key '" + key + "'; allowed keys are "
                        + allowed.stream().sorted().toList()));
    }

    private String requireString(String location, Map<String, Object> map, String key) {
        String value = optionalString(map, key);
        if (value == null) {
            problems.add(location + ": '" + key + "' is required");
        }
        return value;
    }

    private static String optionalString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private List<String> stringList(String location, Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            problems.add(location + ": '" + key + "' must be a list");
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
