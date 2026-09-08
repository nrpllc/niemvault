package gov.niemplatform.build.canonical;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

/**
 * Wires canonical code generation into a module's {@code main} source set.
 *
 * <p>Generated sources are never checked in. The DSL under {@code src/main/canonical} is the
 * versioned artifact (spec §0 rule 5); the records are a build product of it.
 */
public class CanonicalCodegenPlugin implements Plugin<Project> {

    public static final String TASK_NAME = "generateCanonicalTypes";
    public static final String EXTENSION_NAME = "canonicalModel";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);

        CanonicalModelExtension extension =
                project.getExtensions().create(EXTENSION_NAME, CanonicalModelExtension.class);
        extension.getCatalogueClassName().convention("CanonicalTypes");

        TaskProvider<CanonicalCodegenTask> generate =
                project.getTasks().register(TASK_NAME, CanonicalCodegenTask.class, task -> {
                    task.setGroup("canonical model");
                    task.setDescription("Validates the canonical DSL and generates Java records from it.");
                    task.getSourceDirectory().set(project.getLayout().getProjectDirectory().dir("src/main/canonical"));
                    task.getOutputDirectory()
                            .set(project.getLayout().getBuildDirectory().dir("generated/sources/canonical/main/java"));
                    task.getTargetPackage().set(extension.getTargetPackage());
                    task.getCatalogueClassName().set(extension.getCatalogueClassName());
                    task.getExtensionNamespaceRoot().set(extension.getExtensionNamespaceRoot());
                    // Committed manifests, so an air-gapped build can still verify its own NIEM
                    // references (spec section 6, ADR 0011).
                    java.io.File niem = project.getLayout().getProjectDirectory()
                            .dir("src/main/resources/niem").getAsFile();
                    if (niem.isDirectory()) {
                        task.getNiemReleaseDirectory().set(niem);
                    }
                });

        SourceSetContainer sourceSets =
                project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
        sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME)
                .configure(main -> main.getJava().srcDir(generate.flatMap(CanonicalCodegenTask::getOutputDirectory)));
    }
}
