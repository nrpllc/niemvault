package gov.niemplatform.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Content compatibility (spec §7), declared in a module manifest and enforced at load.
 *
 * <p>The enforcement tests matter more than the parsing ones. A declared range that nothing checks
 * is documentation, and §7 asks for ranges "declared explicitly in content metadata <em>and
 * enforced at load</em>".
 */
class ModuleManifestLoaderTest {

    @TempDir
    Path module;

    private static final String VALID = """
            module: law-enforcement
            version: "1.0.0"
            displayName: Law Enforcement
            description: CAD and records content.
            requires:
              platform:
                minimum: "0.1.0"
                below: "1.0.0"
              canonicalModel: "1.0.0"
            canonicalNamespaces: []
            steward: riverton-pd-records
            """;

    private Path write(String yaml) throws IOException {
        Files.writeString(module.resolve(ModuleManifestLoader.MANIFEST_FILE), yaml,
                StandardCharsets.UTF_8);
        return module;
    }

    @Test
    @DisplayName("a manifest declares what the module is and what it needs")
    void loadsValidManifest() throws IOException {
        ModuleManifest manifest = new ModuleManifestLoader().load(write(VALID));

        assertThat(manifest.qualifiedName()).isEqualTo("law-enforcement@1.0.0");
        assertThat(manifest.displayName()).isEqualTo("Law Enforcement");
        assertThat(manifest.steward()).isEqualTo("riverton-pd-records");
        assertThat(manifest.canonicalModelVersion()).isEqualTo(SemanticVersion.parse("1.0.0"));
        assertThat(manifest.platformVersions()).hasToString(">= 0.1.0 and < 1.0.0");
    }

    @Nested
    @DisplayName("the range is enforced, not merely declared")
    class Enforcement {

        @Test
        @DisplayName("content loads on a platform inside its declared range")
        void loadsWithinRange() throws IOException {
            ModuleManifest manifest = new ModuleManifestLoader()
                    .loadFor(write(VALID), SemanticVersion.parse("0.4.2"));

            assertThat(manifest.name()).isEqualTo("law-enforcement");
        }

        @Test
        @DisplayName("a platform older than the minimum is refused, and told which way to move")
        void refusesOlderPlatform() throws IOException {
            Path root = write(VALID);

            assertThatThrownBy(() ->
                    new ModuleManifestLoader().loadFor(root, SemanticVersion.parse("0.0.9")))
                    .isInstanceOf(ContentCompatibilityException.class)
                    .hasMessageContaining("older than the minimum")
                    .hasMessageContaining("upgrade the platform");
        }

        @Test
        @DisplayName("a platform at or beyond the upper bound is refused")
        void refusesNewerPlatform() throws IOException {
            Path root = write(VALID);

            assertThatThrownBy(() ->
                    new ModuleManifestLoader().loadFor(root, SemanticVersion.parse("1.0.0")))
                    .isInstanceOf(ContentCompatibilityException.class)
                    .hasMessageContaining("does not support");
        }

        @Test
        @DisplayName("versions compare numerically, so 0.10 is newer than 0.9")
        void versionsCompareNumerically() throws IOException {
            Path root = write(VALID.replace("minimum: \"0.1.0\"", "minimum: \"0.9.0\""));

            assertThat(new ModuleManifestLoader().loadFor(root, SemanticVersion.parse("0.10.0")))
                    .as("a lexical comparison would call 0.10.0 older than 0.9.0 and refuse it")
                    .isNotNull();
        }

        @Test
        @DisplayName("a release candidate is treated as its release, so upgrades can be tested")
        void qualifiersAreIgnoredForCompatibility() throws IOException {
            Path root = write(VALID);

            assertThat(new ModuleManifestLoader().loadFor(root, SemanticVersion.parse("0.5.0-rc1")))
                    .isNotNull();
        }

        @Test
        @DisplayName("content with no upper bound loads on any newer platform")
        void openEndedRange() throws IOException {
            Path root = write(VALID.replace("    below: \"1.0.0\"\n", ""));

            assertThat(new ModuleManifestLoader().loadFor(root, SemanticVersion.parse("9.9.9")))
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("validated on load")
    class Validation {

        @Test
        @DisplayName("a module with no manifest is refused, not assumed compatible")
        void missingManifest() {
            assertThatThrownBy(() -> new ModuleManifestLoader().load(module))
                    .isInstanceOf(ContentCompatibilityException.class)
                    .hasMessageContaining("every domain module declares its compatibility range");
        }

        @Test
        @DisplayName("a misspelled key is rejected rather than leaving content unbounded")
        void misspelledKeyRejected() throws IOException {
            Path root = write(VALID.replace("  platform:", "  platfrom:"));

            assertThatThrownBy(() -> new ModuleManifestLoader().load(root))
                    .isInstanceOf(ContentCompatibilityException.class)
                    .hasMessageContaining("platfrom");
        }

        @Test
        @DisplayName("a module without a platform range is refused")
        void missingRange() throws IOException {
            Path root = write("""
                    module: law-enforcement
                    version: "1.0.0"
                    """);

            assertThatThrownBy(() -> new ModuleManifestLoader().load(root))
                    .isInstanceOf(ContentCompatibilityException.class)
                    .hasMessageContaining("requires");
        }

        @Test
        @DisplayName("a module without a canonical model version is refused")
        void missingCanonicalModel() throws IOException {
            Path root = write(VALID.replace("  canonicalModel: \"1.0.0\"\n", ""));

            assertThatThrownBy(() -> new ModuleManifestLoader().load(root))
                    .isInstanceOf(ContentCompatibilityException.class)
                    .hasMessageContaining("canonicalModel");
        }

        @Test
        @DisplayName("an inverted range is a nonsense a module cannot declare")
        void invertedRange() throws IOException {
            Path root = write(VALID.replace("below: \"1.0.0\"", "below: \"0.0.1\""));

            assertThatThrownBy(() -> new ModuleManifestLoader().load(root))
                    .isInstanceOf(ContentCompatibilityException.class)
                    .hasMessageContaining("non-empty");
        }

        @Test
        @DisplayName("a module name must be kebab-case, so it is usable as an identifier")
        void badModuleName() throws IOException {
            Path root = write(VALID.replace("module: law-enforcement", "module: Law_Enforcement"));

            assertThatThrownBy(() -> new ModuleManifestLoader().load(root))
                    .isInstanceOf(ContentCompatibilityException.class)
                    .hasMessageContaining("kebab-case");
        }
    }

}
