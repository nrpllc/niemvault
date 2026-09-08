package gov.niemplatform.niem;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The NIEM release the canonical model claims provenance against.
 *
 * <p>Loaded from manifests generated out of the published schemas by {@code
 * tools/niem-manifest/GenerateNiemManifests.java} and committed to the repository. Committed rather
 * than fetched: an air-gapped build has to be able to check its own NIEM references (§6), and a
 * validator that only works with network access is a validator that gets switched off in exactly the
 * environments this platform is built for.
 *
 * <p>Before this existed, {@code niemNamespace} / {@code niemType} / {@code niemElement} were
 * asserted from knowledge with nothing to check them against — the gap ADR 0011 opened and refused
 * to sign off Phase 1 without closing. Asserting a provenance nobody has verified is worse than
 * asserting none, because it looks like a citation.
 *
 * <p><strong>This file is compiled into two builds.</strong> The code generator in {@code
 * build-logic} verifies provenance against a release at build time; the coverage browser reads the
 * same manifests at run time to report what the model covers. Two parsers for one format would
 * drift, and the drift would show up as a coverage report disagreeing with what the build enforces —
 * the failure ADR 0020 names for artifact formats generally. {@code build-logic} adds this
 * directory to its own source set rather than copying the file.
 */
public final class NiemRelease {

    /** One namespace of a NIEM release: what it is called, and everything it declares. */
    public record Namespace(String name, String prefix, String uri, Set<String> types, Set<String> elements) {

        public boolean declaresType(String localName) {
            return types.contains(localName);
        }

        public boolean declaresElement(String localName) {
            return elements.contains(localName);
        }

        /** Every name this namespace declares, types and elements alike. */
        public int declarationCount() {
            return types.size() + elements.size();
        }
    }

    private final Map<String, Namespace> byUri;

    private NiemRelease(Map<String, Namespace> byUri) {
        this.byUri = byUri;
    }

    /** An empty release. Every reference is unverifiable, and the validator says so. */
    public static NiemRelease none() {
        return new NiemRelease(Map.of());
    }

    public boolean isEmpty() {
        return byUri.isEmpty();
    }

    public Optional<Namespace> namespace(String uri) {
        // NIEM namespace URIs are published with a trailing slash and are routinely written without
        // one. Treating those as different namespaces would fail correct references.
        Namespace exact = byUri.get(uri);
        if (exact != null) {
            return Optional.of(exact);
        }
        return Optional.ofNullable(byUri.get(uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri + "/"));
    }

    /** Every namespace the release carries, for an error message that can suggest an alternative. */
    public List<Namespace> namespaces() {
        return List.copyOf(byUri.values());
    }

    /**
     * Loads every {@code *.manifest} in a directory.
     *
     * <p>A missing directory yields an empty release rather than a failure: the model has to remain
     * buildable while a release is being obtained, and the validator reports the resulting
     * unverifiability itself.
     */
    public static NiemRelease load(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return none();
        }
        Map<String, Namespace> namespaces = new LinkedHashMap<>();
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".manifest"))
                    .sorted().toList()) {
                Namespace parsed = parse(
                        Files.readAllLines(file, StandardCharsets.UTF_8),
                        file.getFileName().toString(),
                        file.toString());
                namespaces.put(parsed.uri(), parsed);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read NIEM manifests from " + directory, e);
        }
        return new NiemRelease(namespaces);
    }

    /**
     * Loads the release packaged with the platform.
     *
     * <p>How anything running in a deployed container reads it: the manifests are resources of
     * {@code core:canonical}, so they travel with the jar and need no path to be configured and no
     * volume to be mounted. An operator cannot end up with a coverage report drawn from a different
     * release than the build verified against, because there is only the one copy.
     *
     * <p>The index is a resource too rather than a directory listing, because a classpath is not
     * enumerable in general — inside a fat jar or a module image there is nothing to list.
     */
    public static NiemRelease fromClasspath() {
        return fromClasspath(NiemRelease.class.getClassLoader(), "niem");
    }

    static NiemRelease fromClasspath(ClassLoader loader, String directory) {
        List<String> names;
        try (InputStream index = loader.getResourceAsStream(directory + "/manifests.index")) {
            if (index == null) {
                return none();
            }
            names = new String(index.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the NIEM manifest index", e);
        }

        Map<String, Namespace> namespaces = new LinkedHashMap<>();
        for (String name : names) {
            String resource = directory + "/" + name;
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException(
                            "The NIEM manifest index names " + resource + ", which is not on the classpath");
                }
                List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
                Namespace parsed = parse(lines, name, resource);
                namespaces.put(parsed.uri(), parsed);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + resource, e);
            }
        }
        return new NiemRelease(namespaces);
    }

    private static Namespace parse(List<String> lines, String fileName, String origin) {
        String prefix = null;
        String uri = null;
        Set<String> types = new LinkedHashSet<>();
        Set<String> elements = new LinkedHashSet<>();

        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int space = line.indexOf(' ');
            if (space < 0) {
                continue;
            }
            String value = line.substring(space + 1).trim();
            switch (line.substring(0, space)) {
                case "prefix" -> prefix = value;
                case "namespace" -> uri = value;
                case "T" -> types.add(value);
                case "E" -> elements.add(value);
                default -> { /* Unknown records are ignored so a newer manifest still loads. */ }
            }
        }

        if (prefix == null || uri == null) {
            throw new IllegalStateException(
                    origin + " is not a NIEM manifest: it declares no prefix or namespace");
        }
        return new Namespace(nameOf(fileName), prefix, uri, Set.copyOf(types), Set.copyOf(elements));
    }

    /**
     * The domain's name, from the manifest's file name.
     *
     * <p>{@code maritime-6.0.manifest} is the maritime domain. Taken from the file rather than added
     * as a record inside it so that the two cannot disagree about which domain a manifest is.
     */
    private static String nameOf(String fileName) {
        String base = fileName.endsWith(".manifest")
                ? fileName.substring(0, fileName.length() - ".manifest".length())
                : fileName;
        int version = base.lastIndexOf('-');
        return version > 0 ? base.substring(0, version) : base;
    }

    /**
     * The bare name from a citation, which may or may not carry a prefix.
     *
     * <p>A NIEM reference is written {@code nc:IncidentType} as often as {@code IncidentType}, and
     * the prefix is a convenience for the reader rather than part of the name a namespace declares.
     * Stripping it lives here, with the parser, because both readers of the format need the same
     * answer: the build's verifier and the coverage browser splitting this differently would show a
     * citation as covered in one and unresolved in the other.
     */
    public static String localName(String reference) {
        int prefix = reference.indexOf(':');
        return prefix < 0 ? reference : reference.substring(prefix + 1);
    }

    /**
     * Finds namespaces that declare a name, so a wrong-namespace citation can be corrected rather
     * than merely rejected.
     *
     * <p>This is the message that matters. {@code nc:PersonSexCode} does not exist; {@code j:}
     * declares {@code PersonSexCode}. Saying only "not found" leaves an author to search 6,000
     * names by hand.
     */
    public List<String> whereDeclared(String localName, boolean asType) {
        List<String> found = new ArrayList<>();
        byUri.values().forEach(namespace -> {
            if (asType ? namespace.declaresType(localName) : namespace.declaresElement(localName)) {
                found.add(namespace.prefix() + ":" + localName);
            }
        });
        return List.copyOf(found);
    }
}
