package gov.niemplatform.exchange.api;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalId;
import gov.niemplatform.canonical.meta.CanonicalKind;
import gov.niemplatform.canonical.meta.CanonicalRef;
import gov.niemplatform.canonical.meta.CanonicalRoleDescriptor;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Walks an {@link AssemblySpec} over canonical records and builds documents (ADR 0034).
 *
 * <p>The part that used to be a method per document shape. It is one walk, driven by configuration,
 * and it knows the names of no canonical types at all -- which is the property that makes adding a
 * charge to a submission a change to a YAML file.
 *
 * <h2>Nothing is dropped quietly</h2>
 *
 * <p>A reference that points at a record the assembler was not given is reported rather than
 * skipped. It is a normal thing to happen -- a disposition arrives months after its charge, so an
 * arrest assembled today legitimately has charges with no disposition yet -- and it is also exactly
 * what a broken identity derivation looks like. The two are indistinguishable from inside this
 * class, so it refuses to decide: the caller gets both the documents and the list, and the
 * difference between "not yet" and "never" is a judgement made where the context is.
 *
 * <h2>Order is stable</h2>
 *
 * <p>Documents come out ordered by root identity, and elements within a document by their own, so
 * the same silver produces byte-identical submissions on a re-run. A repository that receives
 * reordered elements cannot tell a resubmission from a correction, and criterion 6's replay claim
 * is worth nothing if replaying produces a different document.
 */
public final class DocumentAssembler {

    private final Map<String, CanonicalTypeDescriptor> model;

    public DocumentAssembler(List<CanonicalTypeDescriptor> model) {
        Map<String, CanonicalTypeDescriptor> byName = new LinkedHashMap<>();
        model.forEach(type -> byName.put(type.name(), type));
        this.model = Map.copyOf(byName);
    }

    /**
     * What one walk produced.
     *
     * @param documents one per record of the assembly's root type, in identity order
     * @param unresolved references that pointed at records not supplied, never silently dropped
     */
    public record Assembly(List<AssembledDocument> documents, List<Unresolved> unresolved) {

        public Assembly {
            documents = List.copyOf(documents);
            unresolved = List.copyOf(unresolved);
        }

        /** Records across every document, for reporting against what was submitted. */
        public int records() {
            return documents.stream().mapToInt(AssembledDocument::size).sum();
        }
    }

    /** A reference that led nowhere. */
    public record Unresolved(
            String fromType, String fromId, String association, String role, String targetRef) {}

    /** Assembles every document the spec's root type yields from these records. */
    public Assembly assemble(AssemblySpec spec, Collection<Record> records) {
        Objects.requireNonNull(spec, "spec");
        Index index = new Index(records);
        List<Unresolved> unresolved = new ArrayList<>();

        List<Record> roots = new ArrayList<>(index.ofType(spec.rootType()));
        roots.sort(Comparator.comparing(DocumentAssembler::identityOf));

        List<AssembledDocument> documents = new ArrayList<>(roots.size());
        for (Record root : roots) {
            documents.add(new AssembledDocument(
                    spec.rootType(), root,
                    gather(spec.rootType(), root, spec.follow(), index, unresolved)));
        }
        return new Assembly(documents, unresolved);
    }

    private Map<String, List<AssembledDocument.Element>> gather(
            String fromType,
            Record from,
            List<AssemblySpec.Follow> follows,
            Index index,
            List<Unresolved> unresolved) {

        if (follows.isEmpty()) {
            return Map.of();
        }
        String fromId = identityOf(from);
        Map<String, List<AssembledDocument.Element>> gathered = new LinkedHashMap<>();

        for (AssemblySpec.Follow step : follows) {
            CanonicalTypeDescriptor association = model.get(step.association());
            if (association == null || association.kind() != CanonicalKind.ASSOCIATION) {
                // Unreachable for a definition that loaded: AssemblySpec.validate rejects both.
                continue;
            }
            Optional<CanonicalRoleDescriptor> far = association.role(step.role());
            Optional<CanonicalRoleDescriptor> near = association.roles().stream()
                    .filter(role -> !role.name().equals(step.role()))
                    .filter(role -> role.targetType().equals(fromType))
                    .findFirst();
            if (far.isEmpty() || near.isEmpty()) {
                continue;
            }

            List<AssembledDocument.Element> elements = new ArrayList<>();
            for (Record link : index.linksTo(step.association(), near.get().name(), fromId)) {
                CanonicalRef reference = refAt(link, step.role());
                if (reference == null) {
                    continue;
                }
                Record target = index.byId(reference.typeName(), reference.id().value());
                if (target == null) {
                    unresolved.add(new Unresolved(fromType, fromId, step.association(),
                            step.role(), reference.toString()));
                    continue;
                }
                elements.add(new AssembledDocument.Element(
                        far.get().targetType(), target, link,
                        gather(far.get().targetType(), target, step.follow(), index, unresolved)));
            }
            elements.sort(Comparator.comparing(element -> identityOf(element.record())));
            gathered.put(step.as(), List.copyOf(elements));
        }
        return Map.copyOf(gathered);
    }

    private static CanonicalRef refAt(Record association, String roleName) {
        if (!association.hasValue(roleName)) {
            return null;
        }
        Object raw = association.raw(roleName);
        return raw instanceof CanonicalRef reference ? reference : null;
    }

    /** A record's canonical identity as text, which is what every index here is keyed on. */
    static String identityOf(Record record) {
        Object raw = record.raw(CanonicalTypeDescriptor.CANONICAL_ID_FIELD);
        if (raw instanceof CanonicalId id) {
            return id.value();
        }
        return raw == null ? "" : String.valueOf(raw);
    }

    /**
     * Records arranged for the two lookups a walk makes.
     *
     * <p>Built once per assembly rather than scanned per step. A walk over an arrest with four
     * charges asks the association index once per charge per level, and a linear scan would turn a
     * nightly submission into an afternoon.
     */
    private final class Index {

        private final Map<String, Map<String, Record>> entitiesByTypeAndId = new LinkedHashMap<>();
        private final Map<String, List<Record>> associationsByRoleAndTarget = new LinkedHashMap<>();

        Index(Collection<Record> records) {
            for (Record record : records) {
                String typeName = record.typeName();
                entitiesByTypeAndId
                        .computeIfAbsent(typeName, key -> new LinkedHashMap<>())
                        .put(identityOf(record), record);

                CanonicalTypeDescriptor descriptor = model.get(typeName);
                if (descriptor == null || descriptor.kind() != CanonicalKind.ASSOCIATION) {
                    continue;
                }
                for (CanonicalRoleDescriptor role : descriptor.roles()) {
                    CanonicalRef reference = refAt(record, role.name());
                    if (reference == null) {
                        continue;
                    }
                    associationsByRoleAndTarget
                            .computeIfAbsent(
                                    key(typeName, role.name(), reference.id().value()),
                                    ignored -> new ArrayList<>())
                            .add(record);
                }
            }
        }

        Collection<Record> ofType(String typeName) {
            return entitiesByTypeAndId.getOrDefault(typeName, Map.of()).values();
        }

        Record byId(String typeName, String id) {
            return entitiesByTypeAndId.getOrDefault(typeName, Map.of()).get(id);
        }

        /** Association records whose {@code roleName} points at {@code id}. */
        List<Record> linksTo(String associationType, String roleName, String id) {
            List<Record> links = associationsByRoleAndTarget
                    .getOrDefault(key(associationType, roleName, id), List.of());
            // Sorted so the elements pulled through them come out the same way every run.
            List<Record> ordered = new ArrayList<>(links);
            ordered.sort(Comparator.comparing(DocumentAssembler::identityOf));
            return ordered;
        }

        private String key(String associationType, String roleName, String id) {
            return associationType + ' ' + roleName + ' ' + id;
        }
    }
}
