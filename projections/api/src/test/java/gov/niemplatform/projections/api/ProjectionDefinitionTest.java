package gov.niemplatform.projections.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.canonical.meta.TenantId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectionDefinitionTest {

    @TempDir
    Path directory;

    private Path write(String yaml) throws IOException {
        Path file = directory.resolve("projection.yaml");
        Files.writeString(file, yaml);
        return file;
    }

    @Test
    @DisplayName("a definition names a type and its settings as text")
    void loads() throws IOException {
        ProjectionDefinition definition = ProjectionDefinition.load(write("""
                projection: leon-ods
                version: "1.0.0"
                type: ods
                settings:
                  jdbcUrl: jdbc:postgresql://localhost:15432/niem
                  port: 15432
                """));

        assertThat(definition.type()).isEqualTo(ProjectionType.ODS);
        assertThat(definition.qualifiedName()).isEqualTo("leon-ods@1.0.0");
        assertThat(definition.requiredSetting("port")).isEqualTo("15432");
        assertThat(definition.setting("user")).isEmpty();
    }

    @Test
    @DisplayName("every problem is reported at once, a misspelled key among them")
    void strict() throws IOException {
        assertThatThrownBy(() -> ProjectionDefinition.load(write("""
                projection: leon-ods
                version: "1.0"
                type: Not_Kebab
                setings:
                  jdbcUrl: x
                """)))
                .isInstanceOf(ProjectionDefinitionException.class)
                .satisfies(e -> assertThat(((ProjectionDefinitionException) e).problems())
                        .hasSize(3)
                        .anySatisfy(p -> assertThat(p).contains("unrecognised key 'setings'"))
                        .anySatisfy(p -> assertThat(p).contains("semver"))
                        .anySatisfy(p -> assertThat(p).contains("kebab-case")));
    }

    @Test
    @DisplayName("a nested setting is refused rather than flattened into something unintended")
    void scalarSettings() throws IOException {
        assertThatThrownBy(() -> ProjectionDefinition.load(write("""
                projection: p
                version: "1.0.0"
                type: search
                settings:
                  url: { host: localhost }
                """)))
                .hasMessageContaining("setting 'url' must be a scalar");
    }

    @Test
    @DisplayName("a secret named but not set is an error, not an anonymous connection")
    void secretMustBeSet() {
        ProjectionDefinition definition = new ProjectionDefinition("p", "1.0.0",
                ProjectionType.ODS, Map.of("passwordEnv", "ODS_PASSWORD"));

        assertThat(definition.secret("passwordEnv", Map.of("ODS_PASSWORD", "s3cret")::get))
                .contains("s3cret");
        assertThatThrownBy(() -> definition.secret("passwordEnv", Map.<String, String>of()::get))
                .hasMessageContaining("$ODS_PASSWORD, which is not set");
        assertThat(definition.secret("tokenEnv", Map.<String, String>of()::get)).isEmpty();
    }

    @Test
    @DisplayName("printing a definition never prints a setting's value")
    void redacts() {
        ProjectionDefinition definition = new ProjectionDefinition("p", "1.0.0",
                ProjectionType.ODS, Map.of("jdbcUrl", "jdbc:postgresql://secret-host/db"));

        assertThat(definition.toString()).contains("jdbcUrl").doesNotContain("secret-host");
    }

    @Test
    @DisplayName("the registry names what it can write when asked for something it cannot")
    void registryNamesWhatItCarries() {
        ProjectionFactory graph = new StubFactory(ProjectionType.GRAPH);
        ProjectionRegistry registry = ProjectionRegistry.of(List.of(graph));

        assertThatThrownBy(() -> registry.open(
                new ProjectionDefinition("p", "1.0.0", ProjectionType.SEARCH, Map.of()), context()))
                .isInstanceOf(ProjectionDefinitionException.class)
                .hasMessageContaining("no projection writer for type 'search'")
                .hasMessageContaining("[graph]");
    }

    @Test
    @DisplayName("two jars claiming one type is an error, not whichever loaded last")
    void duplicateTypes() {
        assertThatThrownBy(() -> ProjectionRegistry.of(List.of(
                new StubFactory(ProjectionType.ODS), new StubFactory(ProjectionType.ODS))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ods is provided by both");
    }

    private static ProjectionContext context() {
        return new ProjectionContext(TenantId.of("us.fl.leon-so"), "leon-so-cad", "m", "1.0.0",
                List.<CanonicalTypeDescriptor>of(), name -> null);
    }

    private record StubFactory(ProjectionType type) implements ProjectionFactory {
        @Override
        public ProjectionWriter open(ProjectionDefinition definition, ProjectionContext context) {
            throw new UnsupportedOperationException("never opened in these tests");
        }
    }
}
