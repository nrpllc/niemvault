package gov.niemplatform.exchange.api;

import gov.niemplatform.canonical.data.Record;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One document, gathered by walking an {@link AssemblySpec} (ADR 0034).
 *
 * <p>A tree of canonical records rather than a wire document: the walk produces this, and the
 * writer turns it into whatever its format is. Keeping the two apart is what lets one assembly
 * serve an XML repository and a JSON one, and it is the same separation §4.3 draws between a
 * connector and a mapping, running the other way.
 *
 * @param rootType the canonical type at the root of the document
 * @param root the root record
 * @param elements what was reached from it, keyed by the {@code as} name the exchange gave them
 */
public record AssembledDocument(String rootType, Record root, Map<String, List<Element>> elements) {

    public AssembledDocument {
        Objects.requireNonNull(rootType, "rootType");
        Objects.requireNonNull(root, "root");
        elements = Map.copyOf(elements);
    }

    /**
     * One record reached by following an association, and whatever was reached from it.
     *
     * <p>The association itself is carried, not discarded. An association is not only a pointer:
     * {@code ArrestSubjectAssociation} states whether that subject was read their rights, and
     * {@code PersonIncidentAssociation} states whether the person was a victim or a suspect. A walk
     * that kept only the endpoints would drop exactly the fields that say what the relationship
     * means, and they would be unrecoverable from the two records it joined.
     *
     * @param typeName the canonical type of the record
     * @param record the record itself
     * @param link the association record that reached it, whose own fields qualify the relationship
     * @param elements further elements, keyed by their {@code as} name
     */
    public record Element(
            String typeName, Record record, Record link, Map<String, List<Element>> elements) {

        public Element {
            Objects.requireNonNull(typeName, "typeName");
            Objects.requireNonNull(record, "record");
            Objects.requireNonNull(link, "link");
            elements = Map.copyOf(elements);
        }
    }

    /** Records in this document, root included, for counting and for reporting. */
    public int size() {
        return 1 + elements.values().stream().mapToInt(AssembledDocument::countAll).sum();
    }

    private static int countAll(List<Element> elements) {
        return elements.stream()
                .mapToInt(element -> 1
                        + element.elements().values().stream()
                                .mapToInt(AssembledDocument::countAll).sum())
                .sum();
    }
}
