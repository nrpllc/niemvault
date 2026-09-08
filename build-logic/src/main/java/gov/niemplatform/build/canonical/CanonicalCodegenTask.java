package gov.niemplatform.build.canonical;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;

/**
 * Generates canonical Java records from the YAML DSL (spec §4.1).
 *
 * <p>The task fails the build on any modelling violation -- missing NIEM provenance, an
 * unjustified extension, a redefined NIEM type -- rather than generating something plausible.
 */
@CacheableTask
public abstract class CanonicalCodegenTask extends DefaultTask {

    /** Directory of {@code *.yaml} canonical type definitions. */
    @InputDirectory
    @SkipWhenEmpty
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getSourceDirectory();

    /** Where generated {@code .java} files are written. */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    /** Java package the generated records are emitted into. */
    @Input
    public abstract Property<String> getTargetPackage();

    /** Name of the generated catalogue class listing every type in this module. */
    @Input
    public abstract Property<String> getCatalogueClassName();

    /** Namespace prefix that separates platform extensions from NIEM-sourced types. */
    @Input
    public abstract Property<String> getExtensionNamespaceRoot();

    /**
     * Manifests of the NIEM release every provenance claim is checked against.
     *
     * <p>Optional so the model stays buildable while a release is being obtained, and an input so
     * that changing the release re-runs the check. Absent, every citation is reported unverifiable
     * rather than quietly accepted (ADR 0011).
     */
    @org.gradle.api.tasks.InputDirectory
    @org.gradle.api.tasks.Optional
    @org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getNiemReleaseDirectory();

    @TaskAction
    public void generate() throws IOException {
        Path sourceDir = getSourceDirectory().get().getAsFile().toPath();
        Path outputDir = getOutputDirectory().get().getAsFile().toPath();

        List<TypeDef> types = new CanonicalDslParser().parseAll(List.of(sourceDir));
        // Refused here rather than degraded quietly. A model whose NIEM citations nothing has
        // checked is the exact failure ADR 0011 exists to prevent, and it is invisible unless the
        // build says so.
        if (!getNiemReleaseDirectory().isPresent()) {
            throw new IllegalStateException(
                    "No NIEM release manifest directory is configured, so the model's provenance "
                            + "claims cannot be verified. See docs/decisions/"
                            + "0011-niem-reference-verification.md.");
        }
        NiemRelease release = NiemRelease.load(getNiemReleaseDirectory().get().getAsFile().toPath());
        if (release.isEmpty()) {
            throw new IllegalStateException(
                    "No NIEM manifests found under "
                            + getNiemReleaseDirectory().get().getAsFile()
                            + "; the model's provenance claims cannot be verified.");
        }
        new CanonicalModelValidator(getExtensionNamespaceRoot().get(), release).validate(types);

        deleteRecursively(outputDir);
        Files.createDirectories(outputDir);

        List<Path> written = new JavaRecordEmitter(getTargetPackage().get(), getCatalogueClassName().get())
                .emit(types, outputDir);

        getLogger().lifecycle("Canonical model: {} type(s) validated, {} source file(s) generated into {}",
                types.size(), written.size(), outputDir);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
