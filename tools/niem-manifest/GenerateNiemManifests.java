/*
 * Generates the NIEM release manifests the canonical model validates its provenance against.
 *
 * Deliberately not a Gradle task and not on any module's classpath. This is the one step in the
 * whole repository that needs the network, and §6 requires the build itself never to: the manifests
 * it writes are committed, and from then on an air-gapped build checks its own citations offline
 * (ADR 0011). Running this is an act of obtaining a release, not part of building against one.
 *
 * Run it with Java 21's single-file source mode -- no build, no dependencies:
 *
 *   java tools/niem-manifest/GenerateNiemManifests.java
 *   java tools/niem-manifest/GenerateNiemManifests.java --domain justice --domain maritime
 *   java tools/niem-manifest/GenerateNiemManifests.java --from-dir path/to/xsd
 *
 * --from-dir reads schemas already downloaded, which is how this is run somewhere that cannot
 * reach OASIS. The output is byte-identical either way.
 *
 * The manifest is a flat text format rather than the XSD itself for two reasons: the parser that
 * reads it is 40 lines and cannot itself be a source of verification bugs, and 67 published schemas
 * pull in each other's imports, so committing them whole would commit the same content many times.
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class GenerateNiemManifests {

    /** The published NIEM 6.0 model. Pinned to a release, never "latest" -- a manifest names what it came from. */
    private static final String RELEASE = "6.0";
    private static final String BASE =
            "https://docs.oasis-open.org/niemopen/niem-model/v6.0/ps02/xsd/";

    /**
     * Where the build looks for them, and a resource directory so they travel in the jar.
     *
     * <p>One copy, read two ways: the code generator verifies against it from disk at build time,
     * and the coverage browser reads it from the classpath at run time. A deployment cannot end up
     * reporting coverage against a different release than the build checked.
     */
    private static final Path OUTPUT =
            Path.of("core", "canonical", "src", "main", "resources", "niem");

    /**
     * niem-core plus every domain NIEM 6.0 publishes.
     *
     * <p>Code lists ({@code codes/}) are deliberately absent. They enumerate permitted values, not
     * types a canonical field can claim provenance against, and adding 38 more namespaces of pure
     * enumeration would bury the domains in the coverage browser without making any citation
     * verifiable that is not already.
     */
    private static final List<Schema> SCHEMAS = List.of(
            new Schema("niem-core", "niem-core.xsd"),
            new Schema("agriculture", "domains/agriculture.xsd"),
            new Schema("biometrics", "domains/biom.xsd"),
            new Schema("cbrn", "domains/cbrn.xsd"),
            new Schema("cyber", "domains/cyber.xsd"),
            new Schema("emergency-management", "domains/emergencyManagement.xsd"),
            new Schema("human-services", "domains/hs.xsd"),
            new Schema("immigration", "domains/immigration.xsd"),
            new Schema("infrastructure-protection", "domains/infrastructureProtection.xsd"),
            new Schema("intelligence", "domains/intelligence.xsd"),
            new Schema("international-trade", "domains/internationalTrade.xsd"),
            new Schema("justice", "domains/justice.xsd"),
            new Schema("learn-dev", "domains/learn-dev.xsd"),
            new Schema("maritime", "domains/maritime.xsd"),
            new Schema("military-operations", "domains/mo.xsd"),
            new Schema("military-operations-usmtf", "domains/mo-usmtf.xsd"),
            new Schema("screening", "domains/screening.xsd"),
            new Schema("surface-transportation", "domains/st.xsd"));

    /** One published schema: the name its manifest takes, and where it lives under the release. */
    record Schema(String name, String path) {
        String url() {
            return BASE + path;
        }

        String manifestFileName() {
            return name + "-" + RELEASE + ".manifest";
        }

        /** The file name as published, for {@code --from-dir}. */
        String fileName() {
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    /** What a schema declares, once parsed. Order is the schema's own, so a diff stays readable. */
    record Declarations(String prefix, String namespace, Set<String> types, Set<String> elements) {}

    public static void main(String[] args) throws Exception {
        List<String> only = new ArrayList<>();
        Path fromDir = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--domain" -> only.add(args[++i]);
                case "--from-dir" -> fromDir = Path.of(args[++i]);
                case "--help", "-h" -> {
                    System.out.println("""
                            Generates NIEM release manifests into core/canonical/src/main/resources/niem.

                              --domain <name>    generate only this one; repeatable
                              --from-dir <path>  read published schemas from disk instead of OASIS
                            """);
                    return;
                }
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    System.exit(2);
                }
            }
        }

        List<Schema> wanted = SCHEMAS.stream()
                .filter(schema -> only.isEmpty() || only.contains(schema.name()))
                .toList();

        if (wanted.isEmpty()) {
            System.err.println("No schema matched " + only + ". Known: "
                    + SCHEMAS.stream().map(Schema::name).toList());
            System.exit(2);
        }

        Files.createDirectories(OUTPUT);
        int written = 0;

        for (Schema schema : wanted) {
            byte[] bytes = fromDir == null
                    ? fetch(schema.url())
                    : Files.readAllBytes(fromDir.resolve(schema.fileName()));

            Declarations declared = parse(bytes, schema);
            Path target = OUTPUT.resolve(schema.manifestFileName());
            String manifest = render(schema, bytes, declared);

            // Rewriting a byte-identical file would churn the working tree and make it look as
            // though a release had changed when nothing had.
            boolean changed = !Files.exists(target)
                    || !Files.readString(target, StandardCharsets.UTF_8).equals(manifest);
            if (changed) {
                Files.writeString(target, manifest, StandardCharsets.UTF_8);
                written++;
            }

            System.out.printf(
                    "%-28s %s  %5d types  %5d elements  %s%n",
                    schema.name(),
                    declared.prefix(),
                    declared.types().size(),
                    declared.elements().size(),
                    changed ? "written" : "unchanged");
        }

        // Written whenever any manifest was, and whenever the set is complete, because a classpath
        // cannot be listed: inside a jar there is no directory to enumerate. Only a full run may
        // rewrite it -- a --domain run must not shrink the index to the one domain it regenerated.
        if (only.isEmpty()) {
            writeIndex();
        }

        System.out.printf("%n%d schema(s) read, %d manifest(s) written to %s%n",
                wanted.size(), written, OUTPUT);
    }

    /** The list of manifests, so {@code NiemRelease.fromClasspath()} knows what to open. */
    private static void writeIndex() throws IOException {
        StringBuilder index = new StringBuilder();
        index.append("# Every manifest in this directory, because a classpath cannot be listed.\n");
        index.append("# Generated by tools/niem-manifest/GenerateNiemManifests.java -- do not edit.\n");
        SCHEMAS.stream()
                .map(Schema::manifestFileName)
                .sorted()
                .forEach(name -> index.append(name).append('\n'));
        Files.writeString(OUTPUT.resolve("manifests.index"), index.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Fetches one published schema.
     *
     * <p>A non-200 is fatal rather than skipped. A missing domain means the release layout has
     * moved, and generating a partial set silently would leave the model verifying against a
     * release that does not exist as described.
     */
    private static byte[] fetch(String url) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(2))
                    .GET()
                    .build();
            HttpResponse<byte[]> response =
                    client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("GET " + url + " returned " + response.statusCode());
            }
            return response.body();
        }
    }

    /**
     * Reads the top-level declarations out of a schema.
     *
     * <p>Only top-level: a type declared inline inside an element is not independently citable, so
     * listing it would let a provenance claim verify against something no instance document can
     * reference by name.
     */
    private static Declarations parse(byte[] bytes, Schema schema) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // A release manifest is generated from a document fetched over the network. It has no
        // business resolving external entities or a DTD to do it.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document;
        try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            document = builder.parse(in);
        }

        Element root = document.getDocumentElement();
        String namespace = root.getAttribute("targetNamespace");
        if (namespace.isBlank()) {
            throw new IllegalStateException(schema.url() + " declares no targetNamespace");
        }

        Set<String> types = new LinkedHashSet<>();
        Set<String> elements = new LinkedHashSet<>();

        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE
                    || !XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(node.getNamespaceURI())) {
                continue;
            }
            Element child = (Element) node;
            String name = child.getAttribute("name");
            if (name.isBlank()) {
                continue;
            }
            switch (child.getLocalName()) {
                case "complexType", "simpleType" -> types.add(name);
                case "element" -> elements.add(name);
                default -> { /* imports, annotations, attribute groups: not citable provenance. */ }
            }
        }

        return new Declarations(prefixFor(root, namespace, schema), namespace, types, elements);
    }

    /**
     * The prefix the release itself uses for a namespace.
     *
     * <p>Taken from the schema's own binding rather than invented here: {@code nc} and {@code j} are
     * what a NIEM author writes, and a manifest that called them something else would make the
     * validator's "did you mean j:PersonSexCode?" useless.
     */
    private static String prefixFor(Element root, String namespace, Schema schema) {
        NamedNodeMap attributes = root.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())
                    && namespace.equals(attribute.getNodeValue())
                    && !XMLConstants.XMLNS_ATTRIBUTE.equals(attribute.getNodeName())) {
                return attribute.getLocalName();
            }
        }
        throw new IllegalStateException(
                schema.url() + " binds no prefix to its own target namespace " + namespace);
    }

    /**
     * Renders the manifest.
     *
     * <p>The header is the provenance of the provenance: which document this was derived from, and
     * its hash, so that "verified against a real release" is a claim someone else can check rather
     * than take on trust.
     */
    private static String render(Schema schema, byte[] bytes, Declarations declared) {
        StringBuilder out = new StringBuilder();
        out.append("# NIEM ").append(RELEASE).append(" release manifest -- ")
                .append(schema.name()).append('\n');
        out.append("#\n");
        out.append("# Generated from the published schema, not written by hand. Committed so the build can\n");
        out.append("# verify every provenance claim offline: an air-gapped build must be able to check its\n");
        out.append("# own NIEM references (spec section 6).\n");
        out.append("#\n");
        out.append("# Regenerate with:\n");
        out.append("#   java tools/niem-manifest/GenerateNiemManifests.java --domain ")
                .append(schema.name()).append('\n');
        out.append("#\n");
        out.append("# source: ").append(schema.url()).append('\n');
        out.append("# sha256: ").append(sha256(bytes)).append('\n');
        out.append("# bytes:  ").append(bytes.length).append('\n');
        out.append("prefix ").append(declared.prefix()).append('\n');
        out.append("namespace ").append(declared.namespace()).append('\n');
        declared.types().stream().sorted().forEach(type -> out.append("T ").append(type).append('\n'));
        declared.elements().stream().sorted()
                .forEach(element -> out.append("E ").append(element).append('\n'));
        return out.toString();
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("SHA-256 unavailable", e));
        }
    }

    private GenerateNiemManifests() {}
}
