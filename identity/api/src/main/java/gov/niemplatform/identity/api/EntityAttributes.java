package gov.niemplatform.identity.api;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The attributes offered to a resolver for one entity (spec §4.5).
 *
 * <p>Deliberately a flat map of strings rather than a canonical record. An external provider --
 * Senzing, IBM entity analytics -- has its own attribute vocabulary and no knowledge of this
 * platform's canonical model, and forcing the canonical type through the SPI would either leak
 * the model into every provider integration or require a translation layer per provider. A map
 * keeps the boundary honest: the caller decides which canonical fields are identity-bearing.
 *
 * @param entityType canonical type being resolved, e.g. {@code Person}
 * @param sourceRecordKey stable identity of the source record this came from, so a resolution can
 *     be traced back without the resolver holding a reference to the record itself
 * @param attributes identity-bearing attributes, unnormalised as the source supplied them
 */
public record EntityAttributes(
        String entityType,
        String sourceRecordKey,
        Map<String, String> attributes) implements Serializable {

    public EntityAttributes {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(sourceRecordKey, "sourceRecordKey");
        attributes = Map.copyOf(attributes);
    }

    public static EntityAttributes of(String entityType, String sourceRecordKey, Map<String, String> attributes) {
        return new EntityAttributes(entityType, sourceRecordKey, attributes);
    }

    /** An attribute, if the source supplied a non-blank value for it. */
    public Optional<String> attribute(String name) {
        return Optional.ofNullable(attributes.get(name)).filter(value -> !value.isBlank());
    }

    /** Whether any of the named attributes carry a value. */
    public boolean hasAny(String... names) {
        for (String name : names) {
            if (attribute(name).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Attribute names and the source record key -- never attribute values.
     *
     * <p>These are names, dates of birth, and government identifiers by definition. Same
     * obligation as {@code Record.toString()}; see ADR 0015.
     */
    @Override
    public String toString() {
        Map<String, Object> shown = new LinkedHashMap<>();
        shown.put("entityType", entityType);
        shown.put("sourceRecordKey", sourceRecordKey);
        shown.put("attributeNames", attributes.keySet());
        return "EntityAttributes" + shown;
    }
}
