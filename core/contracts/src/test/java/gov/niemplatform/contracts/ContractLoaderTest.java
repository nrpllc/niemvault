package gov.niemplatform.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalFieldDescriptor;
import gov.niemplatform.canonical.meta.CanonicalKind;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.canonical.meta.CanonicalTypeResolver;
import gov.niemplatform.canonical.meta.FieldType;
import gov.niemplatform.canonical.meta.NiemProvenance;
import gov.niemplatform.observability.Direction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contracts loaded from disk (spec §4.2), validated on load (§9, ADR 0010).
 *
 * <p>The strictness tests are the point. A contract with a misspelled key that loads happily is
 * a contract that has silently stopped checking the thing it was written to check -- which is
 * worse than having no contract, because the pipeline still looks supervised.
 */
class ContractLoaderTest {

    @TempDir
    Path dir;

    private static final String NIEM_CORE = "https://docs.oasis-open.org/niemopen/ns/model/niem-core/6.0/";

    /** A stand-in for the generated catalogue, so this module tests without depending on codegen. */
    private static CanonicalTypeResolver resolver() {
        return CanonicalTypeResolver.of(List.of(new CanonicalTypeDescriptor(
                "Person",
                "https://niemplatform.gov/canonical/core/1.0",
                "1.0.0",
                CanonicalKind.ENTITY,
                new NiemProvenance(NIEM_CORE, "nc:PersonType", null),
                null,
                List.of(
                        new CanonicalFieldDescriptor("surName", FieldType.STRING, true, false,
                                new NiemProvenance(NIEM_CORE, null, "nc:PersonSurName"), null, List.of(), null),
                        new CanonicalFieldDescriptor("sexCode", FieldType.CODE, false, false,
                                new NiemProvenance(NIEM_CORE, null, "nc:PersonSexCode"), null,
                                List.of("M", "F", "X", "U"), null)),
                List.of())));
    }

    private Path write(String fileName, String yaml) throws IOException {
        Files.writeString(dir.resolve(fileName), yaml, StandardCharsets.UTF_8);
        return dir;
    }

    private List<HopContract> load() {
        return new ContractLoader(resolver()).loadDirectory(dir);
    }

    private static void assertFailsWith(ThrowingCallable action, ContractLoadException.Code expected) {
        assertThatThrownBy(action)
                .isInstanceOf(ContractLoadException.class)
                .satisfies(thrown -> assertThat(((ContractLoadException) thrown).problems())
                        .extracting(ContractLoadException.Problem::code)
                        .contains(expected));
    }

    private static final String VALID = """
            contract: cad-person-to-canonical
            version: "1.2.0"
            hop: map-person

            expects:
              schema: "source:cad-csv/person"
              version: "1.0.0"
              fields:
                - name: NAME_FULL
                  type: string
                  required: true
                - name: DOB
                  type: string
                  pattern: "^\\\\d{2}/\\\\d{2}/\\\\d{4}$"

            emits:
              canonicalType: Person
            """;

    @Test
    @DisplayName("a well-formed contract loads with both schemas")
    void loadsValidContract() throws IOException {
        write("cad-person.yaml", VALID);

        List<HopContract> contracts = load();

        assertThat(contracts).singleElement().satisfies(contract -> {
            assertThat(contract.id()).hasToString("cad-person-to-canonical@1.2.0");
            assertThat(contract.hopId()).isEqualTo("map-person");
            assertThat(contract.expects().id()).isEqualTo("source:cad-csv/person");
            assertThat(contract.expects().fieldNames()).containsExactly("NAME_FULL", "DOB");
        });
    }

    @Test
    @DisplayName("the emitted schema is derived from the canonical model, not restated")
    void emitsDerivedFromCanonicalModel() throws IOException {
        write("cad-person.yaml", VALID);

        Schema emits = load().getFirst().emits();

        assertThat(emits.id()).isEqualTo("https://niemplatform.gov/canonical/core/1.0#Person");
        assertThat(emits.fieldNames()).containsExactly("canonicalId", "surName", "sexCode");
        assertThat(emits.field("sexCode")).get()
                .returns(List.of("M", "F", "X", "U"), FieldExpectation::codeList);
    }

    @Test
    @DisplayName("a loaded contract actually validates records")
    void loadedContractValidates() throws IOException {
        write("cad-person.yaml", VALID);
        HopContract contract = load().getFirst();

        Record good = Record.builder("source:cad-csv/person")
                .set("NAME_FULL", "DOE, JANE M").set("DOB", "03/14/1988").build();
        Record drifted = Record.builder("source:cad-csv/person")
                .set("NAME_FULL", "DOE, JANE M").set("DOB", "1988-03-14").build();

        assertThat(contract.validate(good, Direction.INPUT).isValid()).isTrue();
        assertThat(contract.validate(drifted, Direction.INPUT).failures())
                .singleElement().returns("pattern", gov.niemplatform.observability.ContractViolation.Failure::rule);
    }

    @Test
    @DisplayName("a misspelled key is rejected rather than ignored")
    void misspelledKeyRejected() throws IOException {
        write("bad.yaml", VALID.replace("required: true", "requird: true"));

        assertFailsWith(this::load, ContractLoadException.Code.UNKNOWN_KEY);
    }

    @Test
    @DisplayName("a contract naming an unloaded canonical type fails on load")
    void unresolvedCanonicalType() throws IOException {
        write("bad.yaml", VALID.replace("canonicalType: Person", "canonicalType: Vehicle"));

        assertFailsWith(this::load, ContractLoadException.Code.UNRESOLVED_CANONICAL_TYPE);
    }

    @Test
    @DisplayName("deriving from a canonical type and restating fields is contradictory")
    void canonicalTypeAndFieldsConflict() throws IOException {
        write("bad.yaml", """
                contract: c
                version: "1.0.0"
                hop: h
                expects:
                  schema: "s"
                  version: "1.0.0"
                  fields: []
                emits:
                  canonicalType: Person
                  fields:
                    - name: surName
                      type: string
                """);

        assertFailsWith(this::load, ContractLoadException.Code.INVALID_VALUE);
    }

    @Test
    @DisplayName("a non-semver contract version is rejected")
    void badVersionRejected() throws IOException {
        write("bad.yaml", VALID.replace("version: \"1.2.0\"", "version: \"1.2\""));

        assertFailsWith(this::load, ContractLoadException.Code.INVALID_VALUE);
    }

    @Test
    @DisplayName("an invalid regex fails on load, not on the first record")
    void badPatternRejected() throws IOException {
        write("bad.yaml", VALID.replace("^\\\\d{2}/\\\\d{2}/\\\\d{4}$", "^[unclosed"));

        assertFailsWith(this::load, ContractLoadException.Code.INVALID_VALUE);
    }

    @Test
    @DisplayName("the same contract name and version twice is rejected")
    void duplicateContractRejected() throws IOException {
        write("one.yaml", VALID);
        write("two.yaml", VALID.replace("hop: map-person", "hop: map-person-again"));

        assertFailsWith(this::load, ContractLoadException.Code.DUPLICATE_CONTRACT);
    }

    @Test
    @DisplayName("every problem across every file is reported in one pass")
    void reportsAllProblemsAtOnce() throws IOException {
        write("a.yaml", """
                contract: a
                version: "nope"
                hop: h
                expects:
                  schema: "s"
                  version: "1.0.0"
                  fields:
                    - name: x
                      type: notAType
                emits:
                  canonicalType: Vehicle
                """);

        assertThatThrownBy(this::load)
                .isInstanceOf(ContractLoadException.class)
                .satisfies(thrown -> assertThat(((ContractLoadException) thrown).problems())
                        .hasSizeGreaterThanOrEqualTo(2));
    }
}
