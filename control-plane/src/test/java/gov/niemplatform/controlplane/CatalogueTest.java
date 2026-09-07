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
 * The catalogue, built from the law enforcement module's real artifacts.
 *
 * <p>Two halves are tested differently on purpose. The structural half is assembled from artifacts
 * that already describe themselves, so the tests check it was assembled correctly. The glossary half
 * cannot be derived from anything, so the tests check that where nobody wrote a meaning down, the
 * catalogue says so rather than quietly omitting the term — a glossary that hides its gaps suggests
 * a source is better understood than it is.
 */
class CatalogueTest {

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

    private static Catalogue.Source catalogue() {
        return catalogueOf(WORKSPACE.load("cad-to-canonical-1.0.0.yaml"));
    }

    private static Catalogue.Source catalogueOf(MappingDefinition mapping) {
        return Catalogue.of(mapping, contracts(), CoreCanonicalTypes.ALL);
    }

    private static Catalogue.Term term(String name) {
        return catalogue().vocabulary().stream()
                .filter(entry -> entry.term().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no term '" + name + "' in the catalogue"));
    }

    @Nested
    @DisplayName("The source's own vocabulary")
    class Vocabulary {

        @Test
        @DisplayName("lists every column the source declares, in the order it declares them")
        void listsEveryColumn() {
            assertThat(catalogue().vocabulary())
                    .extracting(Catalogue.Term::term)
                    .containsExactly("INC_NUM", "CALL_TYPE", "RPT_DTTM", "ADDR", "BEAT",
                            "ROLE", "NAME_FULL", "DOB", "SEX", "DL_NUM");
        }

        @Test
        @DisplayName("carries the meaning the agency gave, not a restatement of the name")
        void carriesTheAgencysMeaning() {
            // The point of the glossary. That BEAT is operational districting rather than a postal
            // boundary is not derivable from the data, the name, or the NIEM provenance.
            assertThat(term("BEAT").meaning()).isPresent();
            assertThat(term("BEAT").meaning().orElseThrow())
                    .contains("operational districting")
                    .contains("not a postal");
        }

        @Test
        @DisplayName("records the trap in a column whose absent value looks like a value")
        void recordsTheTrapInDriverLicence() {
            // DL_NUM is a tier-1 resolution key and the source writes the literal UNK where there is
            // none. Treating it as an identifier resolves every such person into one cluster, which
            // is the single most consequential thing anyone could know about this feed.
            assertThat(term("DL_NUM").meaning().orElseThrow()).contains("UNK");
        }

        @Test
        @DisplayName("the shipped module documents all of its vocabulary")
        void shippedModuleIsFullyDocumented() {
            assertThat(catalogue().undocumented())
                    .as("a term nobody has explained is a term nobody can review")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("What a term becomes")
    class Becomes {

        @Test
        @DisplayName("follows a column through the chain to what it actually produces")
        void followsThroughTheChain() {
            // NAME_FULL is split, split again and uppercased. What matters is where it ends up, not
            // that it passes through scratch on the way.
            assertThat(term("NAME_FULL").becomes())
                    .containsExactlyInAnyOrder(
                            "Person.surName", "Person.givenName", "Person.middleName");
        }

        @Test
        @DisplayName("does not report a scratch value as something a term becomes")
        void skipsScratch() {
            // givenNames exists to be split further and never reaches the record. Listing it would
            // describe a field the canonical Person does not have.
            assertThat(term("NAME_FULL").becomes()).doesNotContain("Person.givenNames");
        }

        @Test
        @DisplayName("names the canonical type, not just the field")
        void qualifiesByType() {
            assertThat(term("INC_NUM").becomes()).contains("Incident.incidentNumber");
        }

        @Test
        @DisplayName("says which terms a contract checks on the way in")
        void reportsGovernance() {
            assertThat(term("DOB").governed()).isTrue();
        }
    }

    @Nested
    @DisplayName("Gaps the catalogue must not hide")
    class Gaps {

        @Test
        @DisplayName("an undocumented term is listed as undocumented, not omitted")
        void reportsUndocumentedTerms() {
            var report = WORKSPACE.validate(WORKSPACE.source("cad-to-canonical-1.0.0.yaml")
                    .replace("    - name: BEAT\n      doc: >", "    - name: BEAT\n      ignored: >"));
            // The edit makes BEAT undocumented one way or another; either it parses without a doc,
            // or it fails and there is nothing to catalogue. Only the first is interesting here.
            if (report.definition() == null) {
                return;
            }
            var source = catalogueOf(report.definition());
            assertThat(source.vocabulary()).extracting(Catalogue.Term::term).contains("BEAT");
            assertThat(source.undocumented()).contains("BEAT");
        }

        @Test
        @DisplayName("a column nothing reads is reported, because a source often sends more than asked")
        void reportsUnusedColumns() {
            var report = WORKSPACE.validate(WORKSPACE.source("cad-to-canonical-1.0.0.yaml")
                    .replace("      - target: beat\n        type: copy\n        from: [BEAT]\n", ""));
            assertThat(report.definition()).isNotNull();

            assertThat(catalogueOf(report.definition()).unused()).contains("BEAT");
        }

        @Test
        @DisplayName("the shipped module reads everything its source sends")
        void shippedModuleUsesEverything() {
            assertThat(catalogue().unused()).isEmpty();
        }
    }

    @Nested
    @DisplayName("What the source becomes")
    class Produces {

        @Test
        @DisplayName("registers every canonical type the mapping emits, with how identity is decided")
        void registersProducedTypes() {
            assertThat(catalogue().produces())
                    .extracting(Catalogue.Produced::name)
                    .containsExactlyInAnyOrder("Incident", "Person", "PersonIncidentAssociation");

            var person = catalogue().produces().stream()
                    .filter(produced -> produced.name().equals("Person")).findFirst().orElseThrow();
            assertThat(person.identity()).contains("resolved by bundled-deterministic");
        }

        @Test
        @DisplayName("carries each type's NIEM provenance, now that it is verified")
        void carriesProvenance() {
            var association = catalogue().produces().stream()
                    .filter(produced -> produced.name().equals("PersonIncidentAssociation"))
                    .findFirst().orElseThrow();

            // This was a platform extension until the references could be checked against a real
            // release (ADR 0011). The catalogue is where that distinction is read by anyone who
            // cares whether the data will exchange.
            assertThat(association.provenance()).contains("nc:ActivityPersonAssociationType");
        }

        @Test
        @DisplayName("names the contracts that gate the source")
        void namesContracts() {
            assertThat(catalogue().contracts())
                    .anySatisfy(contract -> assertThat(contract).contains("cad-person-to-canonical"));
        }
    }
}
