package gov.niemplatform.controlplane;

import gov.niemplatform.canonical.meta.CanonicalFieldDescriptor;
import gov.niemplatform.canonical.meta.CanonicalRoleDescriptor;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.canonical.meta.ExtensionJustification;
import gov.niemplatform.canonical.meta.NiemProvenance;
import gov.niemplatform.niem.NiemRelease;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How much of NIEM the canonical model actually stands on.
 *
 * <p>The build already refuses a provenance that does not resolve (ADR 0011), so every citation
 * here is known to be real. What it could not answer is the other direction: given a NIEM release,
 * which of it does this platform touch, what has it extended past, and what has it never gone near.
 * That is the procurement question — "you say you are NIEM-conformant, conformant to how much of
 * it?" — and until now the only honest answer was to read the DSL sources.
 *
 * <p>A report, not a validator. It computes nothing the build enforces and enforces nothing itself;
 * every rule about what a citation may say lives in the code generator, per ADR 0020, and adding a
 * check here would be the second copy that decision exists to prevent. If this disagrees with the
 * build, this is wrong.
 *
 * <p>Coverage is deliberately counted as bare numbers rather than dressed up as a percentage of
 * NIEM. Three canonical types cite a few dozen names out of some twenty-seven thousand; rendered as
 * a percentage that reads like a failing grade, when what it actually says is that a Phase 1 slice
 * of law enforcement does not need maritime vessel voyages. The numbers say the same thing without
 * implying the rest was attempted.
 */
public final class NiemCoverage {

    /** One canonical construct standing on one NIEM name. */
    public record Citation(String canonicalType, String member, String niemName) {

        /** A type-level citation names no member. */
        public boolean isTypeLevel() {
            return member == null;
        }

        /** How it reads in the catalogue: {@code Person.birthDate} or plain {@code Person}. */
        public String where() {
            return member == null ? canonicalType : canonicalType + "." + member;
        }
    }

    /** One NIEM namespace, what it declares, and what the model took from it. */
    public record Namespace(
            String name,
            String prefix,
            String uri,
            int declaredTypes,
            int declaredElements,
            List<Citation> citedTypes,
            List<Citation> citedElements) {

        public int declared() {
            return declaredTypes + declaredElements;
        }

        public int cited() {
            return citedTypes.size() + citedElements.size();
        }

        /** Whether the model stands on this namespace at all. The first thing a reader sorts by. */
        public boolean touched() {
            return cited() > 0;
        }
    }

    /**
     * A canonical construct that deliberately goes beyond NIEM, and the written reason.
     *
     * <p>Shown beside coverage rather than as a footnote to it. An extension is not a gap in
     * conformance; it is a decision, and §4.1 requires it to carry the reason it was taken. A
     * coverage report that listed only what was cited would quietly present the platform's
     * deliberate deviations as omissions.
     */
    public record Extension(String canonicalType, String member, String justification) {

        public String where() {
            return member == null ? canonicalType : canonicalType + "." + member;
        }
    }

    /**
     * A citation the release cannot account for.
     *
     * <p>Expected to be empty always: the build will not generate a model containing one. It is
     * reported rather than assumed away because the alternative is a browser that silently drops
     * whatever it cannot explain, which is the same class of failure as an unverified provenance —
     * the picture looks complete precisely where it is wrong.
     */
    public record Unresolved(String where, String citation, String reason) {}

    /** The whole picture, ordered so the namespaces the model actually uses come first. */
    public record Report(
            List<Namespace> namespaces,
            List<Extension> extensions,
            List<Unresolved> unresolved) {

        public int declared() {
            return namespaces.stream().mapToInt(Namespace::declared).sum();
        }

        public int cited() {
            return namespaces.stream().mapToInt(Namespace::cited).sum();
        }

        public long namespacesTouched() {
            return namespaces.stream().filter(Namespace::touched).count();
        }
    }

    private NiemCoverage() {}

    /**
     * Builds the report.
     *
     * <p>An empty release yields empty namespaces rather than a failure. The platform stays usable
     * while a release is being obtained, exactly as the build does, and a browser that showed
     * nothing is a clearer signal than one that refused to open.
     */
    public static Report of(NiemRelease release, Iterable<CanonicalTypeDescriptor> types) {
        Map<String, List<Citation>> citedTypes = new LinkedHashMap<>();
        Map<String, List<Citation>> citedElements = new LinkedHashMap<>();
        List<Extension> extensions = new ArrayList<>();
        List<Unresolved> unresolved = new ArrayList<>();

        for (CanonicalTypeDescriptor type : types) {
            record Member(String name, NiemProvenance provenance, ExtensionJustification extension) {}

            List<Member> members = new ArrayList<>();
            members.add(new Member(null, type.provenance(), type.extension()));
            for (CanonicalFieldDescriptor field : type.fields()) {
                members.add(new Member(field.name(), field.provenance(), field.extension()));
            }
            for (CanonicalRoleDescriptor role : type.roles()) {
                members.add(new Member(role.name(), role.provenance(), role.extension()));
            }

            for (Member member : members) {
                if (member.extension() != null) {
                    extensions.add(new Extension(
                            type.name(), member.name(), member.extension().text()));
                    continue;
                }
                if (member.provenance() == null) {
                    // Neither provenance nor extension cannot occur -- the descriptor's own
                    // constructor rejects it -- but a canonicalId component reaches here with
                    // neither by design, and silently counting it as coverage would inflate it.
                    continue;
                }
                record(release, type, member.name(), member.provenance(),
                        citedTypes, citedElements, unresolved);
            }
        }

        List<Namespace> namespaces = new ArrayList<>();
        for (NiemRelease.Namespace namespace : release.namespaces()) {
            namespaces.add(new Namespace(
                    namespace.name(),
                    namespace.prefix(),
                    namespace.uri(),
                    namespace.types().size(),
                    namespace.elements().size(),
                    List.copyOf(citedTypes.getOrDefault(namespace.uri(), List.of())),
                    List.copyOf(citedElements.getOrDefault(namespace.uri(), List.of()))));
        }

        // Namespaces the model stands on first, then by name. A reader opens this to see what is
        // used; the seventeen untouched domains are context, not the answer.
        namespaces.sort(Comparator.comparing(Namespace::touched).reversed()
                .thenComparing(Comparator.comparingInt(Namespace::cited).reversed())
                .thenComparing(Namespace::name));

        extensions.sort(Comparator.comparing(Extension::where));

        return new Report(List.copyOf(namespaces), List.copyOf(extensions), List.copyOf(unresolved));
    }

    /** Files one citation against the namespace it names, or reports why it cannot be. */
    private static void record(
            NiemRelease release,
            CanonicalTypeDescriptor type,
            String member,
            NiemProvenance provenance,
            Map<String, List<Citation>> citedTypes,
            Map<String, List<Citation>> citedElements,
            List<Unresolved> unresolved) {

        Citation citation = new Citation(type.name(), member, NiemRelease.localName(provenance.reference()));
        Optional<NiemRelease.Namespace> namespace = release.namespace(provenance.niemNamespace());

        if (namespace.isEmpty()) {
            unresolved.add(new Unresolved(
                    citation.where(),
                    provenance.niemNamespace() + "#" + NiemRelease.localName(provenance.reference()),
                    "the release carries no such namespace"));
            return;
        }

        boolean asType = provenance.niemType() != null;
        NiemRelease.Namespace found = namespace.get();
        boolean declared = asType
                ? found.declaresType(NiemRelease.localName(provenance.reference()))
                : found.declaresElement(NiemRelease.localName(provenance.reference()));

        if (!declared) {
            unresolved.add(new Unresolved(
                    citation.where(),
                    found.prefix() + ":" + NiemRelease.localName(provenance.reference()),
                    found.prefix() + " declares no such " + (asType ? "type" : "element")));
            return;
        }

        // Keyed by the namespace's own URI rather than the citation's, so a citation written
        // without the published trailing slash still lands in the namespace it named.
        (asType ? citedTypes : citedElements)
                .computeIfAbsent(found.uri(), key -> new ArrayList<>())
                .add(citation);
    }
}
