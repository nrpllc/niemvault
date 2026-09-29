package gov.niemplatform.projections.api;

import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.canonical.meta.TenantId;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * What a projection is opened for: whose data, from which source, under which mapping.
 *
 * <p>The tenant is here because a store claims one the first time it is written and refuses every
 * other from then on (ADR 0026). A writer that did not know whose records it was holding could not
 * make that check, and the check is the whole of the isolation guarantee.
 *
 * <p>The model is here because a relational or search projection declares its shape before it
 * holds anything -- a table for every type, a mapping for every index -- and a foreign key from an
 * association to a person needs the person's table whether or not this change set carries one.
 *
 * <p>Source and mapping travel with the writer rather than with each change set: a change set
 * carries a run, and which agency filed these reports is a property of the source the mapping
 * declares, not of the run that happened to land them.
 *
 * @param environment where secrets are read from; {@code System::getenv} outside tests
 */
public record ProjectionContext(
        TenantId tenant,
        String sourceId,
        String mappingName,
        String mappingVersion,
        List<CanonicalTypeDescriptor> model,
        Function<String, String> environment) {

    public ProjectionContext {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(mappingName, "mappingName");
        Objects.requireNonNull(mappingVersion, "mappingVersion");
        Objects.requireNonNull(environment, "environment");
        model = List.copyOf(model);
    }

    /** "name@version", as lineage records it. */
    public String qualifiedMapping() {
        return mappingName + "@" + mappingVersion;
    }
}
