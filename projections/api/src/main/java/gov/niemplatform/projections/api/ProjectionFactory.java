package gov.niemplatform.projections.api;

/**
 * Opens a {@link ProjectionWriter} from a definition (ADR 0035).
 *
 * <p>Discovered through {@link java.util.ServiceLoader}, as connectors (§4.3) and exchange writers
 * (ADR 0034) are, so a backend ships as a jar. A factory rather than a configurable writer because a
 * writer holds a connection, and a registry holding one open connection per backend on the
 * classpath -- whether or not anything asked for it -- would be a registry that fails to start when
 * an unused store is down.
 */
public interface ProjectionFactory {

    /** Which {@code type:} this factory answers to. */
    ProjectionType type();

    /**
     * Opens and verifies a writer: connected, schema in place, tenant claimed.
     *
     * <p>Everything that can be checked before a record lands is checked here. A projection that
     * turned out to be unreachable after a feed had been landed and written to silver leaves an
     * operator replaying to catch gold up.
     *
     * @throws ProjectionDefinitionException if the settings are unusable, naming every problem
     * @throws ProjectionException if the store cannot be reached or belongs to another tenant
     */
    ProjectionWriter open(ProjectionDefinition definition, ProjectionContext context);

    /**
     * The settings a definition of this type carries (ADR 0037). Empty by default.
     *
     * <p>How an authoring surface checks a projection without opening one: opening connects to the
     * store and claims it for a tenant, which is never something a form should do.
     */
    default java.util.List<gov.niemplatform.settings.SettingDescriptor> settings() {
        return java.util.List.of();
    }

    /** One line saying what this projection is, for a palette. */
    default String summary() {
        return "";
    }
}
