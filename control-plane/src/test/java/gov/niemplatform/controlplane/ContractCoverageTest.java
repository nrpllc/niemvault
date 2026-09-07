package gov.niemplatform.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.canonical.core.CoreCanonicalTypes;
import gov.niemplatform.canonical.meta.CanonicalTypeResolver;
import gov.niemplatform.content.SemanticVersion;
import gov.niemplatform.contracts.ContractLoader;
import gov.niemplatform.contracts.HopContract;
import gov.niemplatform.runtime.engine.MappingDefinition;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The check that catches a mapping which would quarantine everything.
 *
 * <p>Every case here is one that passes every other check the platform has: valid YAML, a contract
 * that exists at the version pinned, a transform vocabulary the factory knows. They are wrong only
 * in combination, which is why the check has to look at both artifacts at once.
 */
class ContractCoverageTest {

    private static final Path MODULE = Path.of("").toAbsolutePath().getParent()
            .resolve("modules/law-enforcement/src/main/resources");

    private static final MappingWorkspace WORKSPACE =
            new MappingWorkspace(MODULE, SemanticVersion.parse("0.1.0"));

    private static Map<String, HopContract> contracts() {
        Map<String, HopContract> byHop = new LinkedHashMap<>();
        new ContractLoader(CanonicalTypeResolver.of(CoreCanonicalTypes.ALL))
                .loadDirectory(MODULE.resolve("contracts"))
                .forEach(contract -> byHop.put(contract.hopId(), contract));
        return byHop;
    }

    private static MappingDefinition mappingWith(String... replacements) {
        String yaml = WORKSPACE.source("cad-to-canonical-1.0.0.yaml");
        for (int i = 0; i < replacements.length; i += 2) {
            yaml = yaml.replace(replacements[i], replacements[i + 1]);
        }
        var report = WORKSPACE.validate(yaml);
        assertThat(report.definition()).as("the edited mapping must still parse").isNotNull();
        return report.definition();
    }

    @Nested
    @DisplayName("Content that is already correct")
    class Correct {

        @Test
        @DisplayName("the shipped mapping satisfies every contract it names")
        void shippedContentIsClean() {
            // If this ever fails, the module ships content that would quarantine its own feed.
            assertThat(ContractCoverage.check(mappingWith(), contracts())).isEmpty();
        }

        @Test
        @DisplayName("does not report the fields the platform fills in itself")
        void ignoresPlatformSuppliedFields() {
            // canonicalId is assigned by identity resolution after the steps run, and an
            // association's roles are filled from the hops it depends on. A check that is wrong on
            // correct content gets switched off, and then it catches nothing at all.
            assertThat(ContractCoverage.check(mappingWith(), contracts()))
                    .extracting(ContractCoverage.Gap::field)
                    .doesNotContain("canonicalId", "person", "incident");
        }
    }

    @Nested
    @DisplayName("A required field nothing produces")
    class RequiredNotProduced {

        @Test
        @DisplayName("is reported, because otherwise every record quarantines at deploy")
        void reportsAMissingRequiredField() {
            // Person requires surName. Rename the step that writes it and the mapping still loads
            // perfectly -- and would reject the entire feed.
            var gaps = ContractCoverage.check(
                    mappingWith("- target: surName\n        type: upper", "- target: surNam\n        type: upper",
                            "- target: surName\n        type: splitIndex", "- target: surNam\n        type: splitIndex"),
                    contracts());

            assertThat(gaps)
                    .filteredOn(gap -> gap.kind() == ContractCoverage.Kind.REQUIRED_NOT_PRODUCED)
                    .singleElement()
                    .satisfies(gap -> {
                        assertThat(gap.field()).isEqualTo("surName");
                        assertThat(gap.hopId()).isEqualTo("map-person");
                        assertThat(gap.message()).contains("every record would be quarantined");
                    });
        }
    }

    @Nested
    @DisplayName("A field written that the record does not declare")
    class ProducedNotDeclared {

        @Test
        @DisplayName("is reported with what to do about it")
        void reportsAnUndeclaredTarget() {
            var gaps = ContractCoverage.check(
                    mappingWith("- target: beat", "- target: patrolBeat"), contracts());

            assertThat(gaps)
                    .filteredOn(gap -> gap.kind() == ContractCoverage.Kind.PRODUCED_NOT_DECLARED)
                    .extracting(ContractCoverage.Gap::field)
                    .containsExactly("patrolBeat");
            assertThat(gaps.getFirst().message()).contains("declare it scratch");
        }

        @Test
        @DisplayName("is not reported for a value the hop declares as scratch")
        void scratchIsNotAGap() {
            // givenNames is written, read by later steps, and never reaches the record. That is the
            // documented way to hold working state, not an error.
            assertThat(ContractCoverage.check(mappingWith(), contracts()))
                    .extracting(ContractCoverage.Gap::field)
                    .doesNotContain("givenNames");
        }
    }

    @Nested
    @DisplayName("A column the inbound gate does not allow")
    class ReadNotExpected {

        @Test
        @DisplayName("is reported, because the record is rejected before the step ever runs")
        void reportsAnUndeclaredRead() {
            // The mapping declares more columns than any one contract expects. Reading one the
            // contract does not list means the inbound gate rejects the record first.
            var gaps = ContractCoverage.check(
                    mappingWith("emits: \"source:cad-csv/incident-person\"\n",
                            "emits: \"source:cad-csv/incident-person\"\n"),
                    strippedOf("BEAT"));

            assertThat(gaps)
                    .filteredOn(gap -> gap.kind() == ContractCoverage.Kind.READ_NOT_EXPECTED)
                    .extracting(ContractCoverage.Gap::field)
                    .contains("BEAT");
        }

        /** The module's contracts, with one column removed from map-incident's inbound schema. */
        private Map<String, HopContract> strippedOf(String column) {
            Map<String, HopContract> byHop = new LinkedHashMap<>();
            var loader = new ContractLoader(CanonicalTypeResolver.of(CoreCanonicalTypes.ALL));
            for (var file : java.util.Objects.requireNonNull(
                    MODULE.resolve("contracts").toFile().listFiles())) {
                String yaml;
                try {
                    yaml = java.nio.file.Files.readString(file.toPath());
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                if (file.getName().contains("incident-to-canonical")) {
                    yaml = yaml.replace("    - name: " + column + "\n      type: string\n", "");
                }
                HopContract contract = loader.load(new java.io.ByteArrayInputStream(
                        yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)), file.getName());
                byHop.put(contract.hopId(), contract);
            }
            return byHop;
        }
    }
}
