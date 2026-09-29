package gov.niemplatform.pipeline;

import gov.niemplatform.canonical.core.CoreCanonicalTypes;
import gov.niemplatform.connectors.api.ConnectorRegistry;
import gov.niemplatform.connectors.api.SourceDefinition;
import gov.niemplatform.exchange.api.ExchangeDefinition;
import gov.niemplatform.exchange.api.ExchangeRegistry;
import gov.niemplatform.projections.api.ProjectionDefinition;
import gov.niemplatform.projections.api.ProjectionRegistry;
import gov.niemplatform.runtime.engine.MappingDefinition;
import gov.niemplatform.runtime.engine.MappingLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns a pipeline's names into the artifacts they refer to, and checks they fit together
 * (ADR 0037).
 *
 * <p>The checks are the ones {@code niem run} already makes when it is handed the same files by
 * flag -- a source definition for the mapping's source, an exchange for the mapping's source, a
 * transport and a projection this deployment carries -- made once, here, so the designer and the
 * command line cannot disagree about whether a pipeline is runnable.
 *
 * <p>Two levels of strictness, because two callers need different answers. {@code niem run} needs
 * every destination found: running a pipeline that silently skipped a projection it could not
 * locate would leave that store behind with nothing reporting it. An author in the designer,
 * working on a module that ships no deployment's database settings, needs "this one is supplied by
 * the deployment" said as a note rather than a failure.
 */
public final class PipelineResolver {

    /** How a destination that cannot be found is treated. */
    public enum Strictness {
        /** Every name must resolve. For running. */
        RUN,
        /** A projection or exchange found nowhere is a note, not a problem. For authoring. */
        AUTHORING
    }

    /** What a pipeline resolved to, and what stops it running. */
    public record Resolution(
            PipelineDefinition pipeline,
            Optional<Path> sourceFile,
            Optional<SourceDefinition> source,
            Optional<Path> mappingFile,
            Optional<MappingDefinition> mapping,
            List<Path> projectionFiles,
            List<ProjectionDefinition> projections,
            List<Path> exchangeFiles,
            List<ExchangeDefinition> exchanges,
            List<Problem> problems,
            List<String> notes) {

        public Resolution {
            projectionFiles = List.copyOf(projectionFiles);
            projections = List.copyOf(projections);
            exchangeFiles = List.copyOf(exchangeFiles);
            exchanges = List.copyOf(exchanges);
            problems = List.copyOf(problems);
            notes = List.copyOf(notes);
        }

        public boolean runnable() {
            return problems.isEmpty();
        }
    }

    /**
     * One thing wrong, attributed to the stage it belongs to so a designer can mark that stage.
     *
     * @param stage {@code pipeline}, {@code origin}, {@code mapping}, or {@code projection:<name>} /
     *     {@code exchange:<name>}
     */
    public record Problem(String stage, String detail) {
        @Override
        public String toString() {
            return stage + ": " + detail;
        }
    }

    private final ArtifactCatalog catalog;
    private final ConnectorRegistry connectors;
    private final ProjectionRegistry projections;
    private final ExchangeRegistry exchanges;

    public PipelineResolver(
            ArtifactCatalog catalog,
            ConnectorRegistry connectors,
            ProjectionRegistry projections,
            ExchangeRegistry exchanges) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.connectors = Objects.requireNonNull(connectors, "connectors");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.exchanges = Objects.requireNonNull(exchanges, "exchanges");
    }

    public ArtifactCatalog catalog() {
        return catalog;
    }

    public Resolution resolve(PipelineDefinition pipeline, Strictness strictness) {
        List<Problem> problems = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        // --- origin --------------------------------------------------------------------
        Path sourceFile = null;
        SourceDefinition source = null;
        String sourceKey = pipeline.originSource() + "/" + pipeline.originInstance();
        Optional<ArtifactCatalog.Entry> sourceEntry =
                one(ArtifactCatalog.Kind.SOURCE, sourceKey, "origin", "source definition", problems);
        if (sourceEntry.isPresent()) {
            sourceFile = sourceEntry.get().file();
            try {
                source = SourceDefinition.load(sourceFile);
                if (connectors.forType(source.type()).isEmpty()) {
                    problems.add(new Problem("origin", "transport '" + source.type() + "' is not on this "
                            + "deployment's classpath; it can read " + connectors.availableTypes()));
                }
            } catch (RuntimeException e) {
                problems.add(new Problem("origin", e.getMessage()));
            }
            if (sourceEntry.get().deployment()) {
                notes.add("origin: " + sourceKey + " comes from the deployment's own definitions ("
                        + sourceFile.getFileName() + ")");
            }
        }

        // --- the mapping ---------------------------------------------------------------
        Path mappingFile = null;
        MappingDefinition mapping = null;
        Optional<ArtifactCatalog.Entry> mappingEntry =
                one(ArtifactCatalog.Kind.MAPPING, pipeline.mapping(), "mapping", "mapping", problems);
        if (mappingEntry.isPresent()) {
            mappingFile = mappingEntry.get().file();
            try {
                mapping = new MappingLoader().load(mappingFile);
                if (!mapping.sourceId().equals(pipeline.originSource())) {
                    // The check run makes between a source definition and a mapping (ADR 0029), for
                    // the same reason: the records would map cleanly and describe the wrong feed.
                    problems.add(new Problem("mapping", "is written against source '" + mapping.sourceId()
                            + "', but the origin is '" + pipeline.originSource() + "'"));
                }
            } catch (RuntimeException e) {
                problems.add(new Problem("mapping", e.getMessage()));
            }
        }

        // --- destinations --------------------------------------------------------------
        List<Path> projectionFiles = new ArrayList<>();
        List<ProjectionDefinition> projectionDefinitions = new ArrayList<>();
        for (String name : pipeline.projections()) {
            String stage = "projection:" + name;
            Optional<ArtifactCatalog.Entry> entry = destination(
                    ArtifactCatalog.Kind.PROJECTION, name, stage, "projection", strictness, problems, notes);
            if (entry.isEmpty()) {
                continue;
            }
            try {
                ProjectionDefinition definition = ProjectionDefinition.load(entry.get().file());
                if (!projections.availableTypes().contains(definition.type())) {
                    problems.add(new Problem(stage, "type '" + definition.type() + "' is not on this "
                            + "deployment's classpath; it can write " + projections.availableTypes()));
                }
                projectionFiles.add(entry.get().file());
                projectionDefinitions.add(definition);
            } catch (RuntimeException e) {
                problems.add(new Problem(stage, e.getMessage()));
            }
        }

        List<Path> exchangeFiles = new ArrayList<>();
        List<ExchangeDefinition> exchangeDefinitions = new ArrayList<>();
        if (pipeline.exchanges().size() > 1) {
            problems.add(new Problem("pipeline", "names " + pipeline.exchanges().size() + " exchanges; "
                    + "a run submits through one. Split the pipeline, one per repository"));
        }
        for (String name : pipeline.exchanges()) {
            String stage = "exchange:" + name;
            Optional<ArtifactCatalog.Entry> entry = destination(
                    ArtifactCatalog.Kind.EXCHANGE, name, stage, "exchange", strictness, problems, notes);
            if (entry.isEmpty()) {
                continue;
            }
            try {
                ExchangeDefinition definition =
                        ExchangeDefinition.load(entry.get().file(), CoreCanonicalTypes.ALL);
                if (exchanges.forType(definition.type()).isEmpty()) {
                    problems.add(new Problem(stage, "wire format '" + definition.type() + "' is not on "
                            + "this deployment's classpath; it can submit to " + exchanges.availableTypes()));
                }
                if (!definition.sourceId().equals(pipeline.originSource())) {
                    problems.add(new Problem(stage, "sends source '" + definition.sourceId()
                            + "', but the origin is '" + pipeline.originSource() + "' (ADR 0034)"));
                }
                exchangeFiles.add(entry.get().file());
                exchangeDefinitions.add(definition);
            } catch (RuntimeException e) {
                problems.add(new Problem(stage, e.getMessage()));
            }
        }

        if (pipeline.projections().isEmpty() && pipeline.exchanges().isEmpty()) {
            notes.add("pipeline: no destinations -- a run lands and maps, and the records reach silver "
                    + "only if the deployment configures it");
        }

        return new Resolution(pipeline, Optional.ofNullable(sourceFile), Optional.ofNullable(source),
                Optional.ofNullable(mappingFile), Optional.ofNullable(mapping),
                projectionFiles, projectionDefinitions, exchangeFiles, exchangeDefinitions, problems, notes);
    }

    private Optional<ArtifactCatalog.Entry> one(
            ArtifactCatalog.Kind kind, String reference, String stage, String what, List<Problem> problems) {
        List<ArtifactCatalog.Entry> found = catalog.find(kind, reference);
        if (found.size() == 1) {
            return Optional.of(found.get(0));
        }
        if (found.isEmpty()) {
            problems.add(new Problem(stage, "no " + what + " '" + reference + "' in the module or the "
                    + "deployment's definitions" + unreadableSuffix()));
        } else {
            problems.add(new Problem(stage, ambiguous(what, reference, found)));
        }
        return Optional.empty();
    }

    private Optional<ArtifactCatalog.Entry> destination(
            ArtifactCatalog.Kind kind, String reference, String stage, String what,
            Strictness strictness, List<Problem> problems, List<String> notes) {
        List<ArtifactCatalog.Entry> found = catalog.find(kind, reference);
        if (found.size() == 1) {
            return Optional.of(found.get(0));
        }
        if (found.size() > 1) {
            problems.add(new Problem(stage, ambiguous(what, reference, found)));
        } else if (strictness == Strictness.RUN) {
            problems.add(new Problem(stage, "no " + what + " '" + reference + "' in the module or the "
                    + "deployment's definitions (--artifacts)" + unreadableSuffix()));
        } else {
            notes.add(stage + ": not in this module; a deployment supplies it (niem run --artifacts)");
        }
        return Optional.empty();
    }

    private static String ambiguous(String what, String reference, List<ArtifactCatalog.Entry> found) {
        return "'" + reference + "' matches " + found.size() + " " + what + "s ("
                + found.stream().map(e -> e.qualifiedName() + " in " + e.file().getFileName()).toList()
                + "); name one with @version";
    }

    private String unreadableSuffix() {
        return catalog.unreadable().isEmpty() ? ""
                : ". Unreadable files that might have held it: " + catalog.unreadable();
    }
}
