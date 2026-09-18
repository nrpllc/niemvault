package gov.niemplatform.exchange.cch;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalId;
import gov.niemplatform.canonical.meta.CanonicalRef;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.exchange.api.AssembledDocument;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns an assembled document into JSON, without knowing what is in it (ADR 0034).
 *
 * <p>The whole of the difference from the writer this replaced. That one had a method per canonical
 * type and a line per field -- {@code put(node, "givenName", field(typed, record, "givenName"))} --
 * so the set of things that could be submitted was fixed at compile time. This walks whatever the
 * assembly produced. There is not one canonical type name anywhere in this class, and that is the
 * property worth protecting: adding a charge to a submission is a change to a YAML file.
 *
 * <h2>Values, and the one that is not a value</h2>
 *
 * <p>Fields are rendered by their own type -- a date as ISO-8601, a number as a number, a boolean
 * as a boolean -- so a receiver parsing this does not have to know that the platform stringifies
 * everything. The canonical identity is lifted out of the field map and onto the node, because it
 * is how the far side upserts rather than something it stores.
 *
 * <p>An absent field is absent, never null. The receiver has no way to distinguish "the platform
 * did not send this" from "the platform sent nothing for it", and a null in JSON is a promise of
 * the second that this side cannot make.
 *
 * <h2>Stable output</h2>
 *
 * <p>Field names are ordered, so the same silver produces the same bytes. A repository that
 * receives a reordered document cannot tell a resubmission from a correction.
 */
final class DocumentJson {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private DocumentJson() {}

    /** One document as a JSON object. */
    static ObjectNode of(AssembledDocument document) {
        ObjectNode node = NODES.objectNode();
        node.put("type", document.rootType());
        node.put("canonicalId", identityOf(document.root()));
        node.set("fields", fieldsOf(document.root()));
        appendElements(node, document.elements());
        return node;
    }

    private static void appendElements(
            ObjectNode parent, Map<String, List<AssembledDocument.Element>> elements) {
        if (elements.isEmpty()) {
            return;
        }
        ObjectNode container = parent.putObject("elements");
        // Sorted for the same reason fields are: a stable document is a comparable one.
        new TreeMap<>(elements).forEach((name, values) -> {
            ArrayNode array = container.putArray(name);
            values.forEach(element -> array.add(of(element)));
        });
    }

    private static ObjectNode of(AssembledDocument.Element element) {
        ObjectNode node = NODES.objectNode();
        node.put("type", element.typeName());
        node.put("canonicalId", identityOf(element.record()));
        node.set("fields", fieldsOf(element.record()));

        // What the relationship itself says. An association is not only a pointer: whether a
        // subject was read their rights is a fact about this arrest and this person, and it exists
        // nowhere but on the association that joins them.
        ObjectNode linkFields = fieldsOf(element.link());
        if (!linkFields.isEmpty()) {
            ObjectNode via = node.putObject("via");
            via.put("type", element.link().typeName());
            via.set("fields", linkFields);
        }
        appendElements(node, element.elements());
        return node;
    }

    /**
     * A record's own values, minus its identity and minus its role references.
     *
     * <p>Role references are how the walk got here; repeating them inside the element would invite
     * a receiver to reconstruct the graph from ids rather than from the nesting it was sent.
     */
    private static ObjectNode fieldsOf(Record record) {
        ObjectNode node = NODES.objectNode();
        new TreeMap<>(record.values()).forEach((name, value) -> {
            if (name.equals(CanonicalTypeDescriptor.CANONICAL_ID_FIELD)
                    || value == null
                    || value instanceof CanonicalRef) {
                return;
            }
            put(node, name, value);
        });
        return node;
    }

    private static void put(ObjectNode node, String name, Object value) {
        switch (value) {
            case String text -> {
                if (!text.isEmpty()) {
                    node.put(name, text);
                }
            }
            case Boolean flag -> node.put(name, flag);
            case Long number -> node.put(name, number);
            case Integer number -> node.put(name, number.longValue());
            case BigDecimal number -> node.put(name, number);
            case Instant instant -> node.put(name, instant.toString());
            case LocalDate date -> node.put(name, date.toString());
            case CanonicalId id -> node.put(name, id.value());
            // Anything the canonical DSL grows later still crosses, as text, rather than being
            // dropped for being unrecognised here.
            default -> node.put(name, String.valueOf(value));
        }
    }

    private static String identityOf(Record record) {
        Object raw = record.raw(CanonicalTypeDescriptor.CANONICAL_ID_FIELD);
        if (raw instanceof CanonicalId id) {
            return id.value();
        }
        return raw == null ? "" : String.valueOf(raw);
    }
}
