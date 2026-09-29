package gov.niemplatform.cli;

import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.canonical.meta.TenantId;
import gov.niemplatform.projections.api.ProjectionContext;
import gov.niemplatform.projections.api.ProjectionDefinition;
import gov.niemplatform.projections.api.ProjectionRegistry;
import gov.niemplatform.projections.api.ProjectionWriter;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Opens the projections named by {@code --projection} files (ADR 0035), for {@code run} and
 * {@code replay} alike.
 *
 * <p>One place, because the two commands must open gold the same way. They write it under opposite
 * obligations -- run applies, replay rebuilds -- and a projection that each command configured in
 * its own fashion is how a replay comes to rebuild a different store from the one ingest fills.
 *
 * <p>All or nothing. Every writer is opened, connected and tenant-checked before the caller lands a
 * single record, and if any cannot be, the ones already opened are closed and nothing proceeds: a
 * run that discovered its third projection was unreachable after landing would have filled two of
 * three stores and left an operator reconciling them.
 */
final class ProjectionTargets {

    private ProjectionTargets() {}

    static List<ProjectionWriter> open(
            List<Path> files,
            String tenant,
            String sourceId,
            String mappingName,
            String mappingVersion,
            Map<String, CanonicalTypeDescriptor> canonicalTypes,
            PrintStream out) {

        if (files == null || files.isEmpty()) {
            return List.of();
        }
        // The artifact set indexes types under more than one key; the model is the distinct values.
        List<CanonicalTypeDescriptor> model =
                List.copyOf(new LinkedHashSet<>(canonicalTypes.values()));
        ProjectionContext context = new ProjectionContext(
                TenantId.of(tenant), sourceId, mappingName, mappingVersion, model, System::getenv);
        ProjectionRegistry registry = ProjectionRegistry.discover();

        List<ProjectionDefinition> definitions = files.stream().map(ProjectionDefinition::load).toList();
        List<ProjectionWriter> opened = new ArrayList<>();
        try {
            for (ProjectionDefinition definition : definitions) {
                opened.add(registry.open(definition, context));
                out.printf("Projection '%s' (%s) is open for %s.%n",
                        definition.qualifiedName(), definition.type(), context.tenant());
            }
            return opened;
        } catch (RuntimeException e) {
            opened.forEach(ProjectionWriter::close);
            throw e;
        }
    }
}
