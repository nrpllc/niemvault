package gov.niemplatform.projections.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Discovers projection backends on the classpath (ADR 0035).
 *
 * <p>A duplicate {@link ProjectionType} across two jars is an error rather than last-one-wins, for
 * the reason {@code ExchangeRegistry} gives: which implementation you got would otherwise depend on
 * classpath order, and finding that out from a graph written by the wrong writer is miserable.
 */
public final class ProjectionRegistry {

    private final Map<ProjectionType, ProjectionFactory> byType;

    private ProjectionRegistry(Map<ProjectionType, ProjectionFactory> byType) {
        this.byType = byType;
    }

    public static ProjectionRegistry discover() {
        return of(ServiceLoader.load(ProjectionFactory.class));
    }

    public static ProjectionRegistry of(Iterable<? extends ProjectionFactory> factories) {
        Map<ProjectionType, ProjectionFactory> found = new LinkedHashMap<>();
        List<String> conflicts = new ArrayList<>();
        for (ProjectionFactory factory : factories) {
            ProjectionFactory previous = found.putIfAbsent(factory.type(), factory);
            if (previous != null) {
                conflicts.add("%s is provided by both %s and %s".formatted(
                        factory.type(), previous.getClass().getName(), factory.getClass().getName()));
            }
        }
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException(
                    "Conflicting projection registrations: " + String.join("; ", conflicts));
        }
        return new ProjectionRegistry(Map.copyOf(found));
    }

    /** The factory for a type, if this deployment carries one -- for describing it, not opening it. */
    public java.util.Optional<ProjectionFactory> forType(ProjectionType type) {
        return java.util.Optional.ofNullable(byType.get(type));
    }

    /** Every projection this deployment can write, in no particular order. */
    public List<ProjectionType> availableTypes() {
        return byType.keySet().stream().sorted(java.util.Comparator.comparing(ProjectionType::id)).toList();
    }

    /**
     * Opens the writer a definition names.
     *
     * @throws ProjectionDefinitionException naming what this deployment does carry -- an air-gapped
     *     operator needs to know whether they are missing a jar or have misspelled a type
     */
    public ProjectionWriter open(ProjectionDefinition definition, ProjectionContext context) {
        ProjectionFactory factory = byType.get(definition.type());
        if (factory == null) {
            throw new ProjectionDefinitionException(null, List.of(
                    "no projection writer for type '" + definition.type() + "' is on the classpath. "
                            + "This deployment can write: " + availableTypes()));
        }
        return factory.open(definition, context);
    }
}
