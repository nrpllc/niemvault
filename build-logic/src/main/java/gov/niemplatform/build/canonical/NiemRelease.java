package gov.niemplatform.build.canonical;

import java.io.IOException;
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
 * <p>Loaded from manifests generated out of the published schemas and committed to the repository.
 * Committed rather than fetched: an air-gapped build has to be able to check its own NIEM references
 * (§6), and a validator that only works with network access is a validator that gets switched off in
 * exactly the environments this platform is built for.
 *
 * <p>Before this existed, {@code niemNamespace} / {@code niemType} / {@code niemElement} were
 * asserted from knowledge with nothing to check them against — the gap ADR 0011 opened and refused
 * to sign off Phase 1 without closing. Asserting a provenance nobody has verified is worse than
 * asserting none, because it looks like a citation.
 */
final class NiemRelease {

    /** One namespace of a NIEM release: what it is called, and everything it declares. */
    record Namespace(String prefix, String uri, Set<String> types, Set<String> elements) {

        boolean declaresType(String localName) {
            return types.contains(localName);
        }

        boolean declaresElement(String localName) {
            return elements.contains(localName);
        }
    }

    private final Map<String, Namespace> byUri;

    private NiemRelease(Map<String, Namespace> byUri) {
        this.byUri = byUri;
    }

    /** An empty release. Every reference is unverifiable, and the validator says so. */
    static NiemRelease none() {
        return new NiemRelease(Map.of());
    }

    boolean isEmpty() {
        return byUri.isEmpty();
    }

    Optional<Namespace> namespace(String uri) {
        // NIEM namespace URIs are published with a trailing slash and are routinely written without
        // one. Treating those as different namespaces would fail correct references.
        Namespace exact = byUri.get(uri);
        if (exact != null) {
            return Optional.of(exact);
        }
        return Optional.ofNullable(byUri.get(uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri + "/"));
    }

    /** Every namespace the release carries, for an error message that can suggest an alternative. */
    List<Namespace> namespaces() {
        return List.copyOf(byUri.values());
    }

    /**
     * Loads every {@code *.manifest} in a directory.
     *
     * <p>A missing directory yields an empty release rather than a failure: the model has to remain
     * buildable while a release is being obtained, and the validator reports the resulting
     * unverifiability itself.
     */
    static NiemRelease load(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return none();
        }
        Map<String, Namespace> namespaces = new LinkedHashMap<>();
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".manifest"))
                    .sorted().toList()) {
                Namespace parsed = parse(file);
                namespaces.put(parsed.uri(), parsed);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read NIEM manifests from " + directory, e);
        }
        return new NiemRelease(namespaces);
    }

    private static Namespace parse(Path file) {
        String prefix = null;
        String uri = null;
        Set<String> types = new LinkedHashSet<>();
        Set<String> elements = new LinkedHashSet<>();

        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
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
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }

        if (prefix == null || uri == null) {
            throw new IllegalStateException(
                    file + " is not a NIEM manifest: it declares no prefix or namespace");
        }
        return new Namespace(prefix, uri, Set.copyOf(types), Set.copyOf(elements));
    }

    /**
     * Finds namespaces that declare a name, so a wrong-namespace citation can be corrected rather
     * than merely rejected.
     *
     * <p>This is the message that matters. {@code nc:PersonSexCode} does not exist; {@code j:}
     * declares {@code PersonSexCode}. Saying only "not found" leaves an author to search 6,000
     * names by hand.
     */
    List<String> whereDeclared(String localName, boolean asType) {
        List<String> found = new ArrayList<>();
        byUri.values().forEach(namespace -> {
            if (asType ? namespace.declaresType(localName) : namespace.declaresElement(localName)) {
                found.add(namespace.prefix() + ":" + localName);
            }
        });
        return List.copyOf(found);
    }
}
