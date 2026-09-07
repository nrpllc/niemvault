package gov.niemplatform.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.canonical.core.CoreCanonicalTypes;
import gov.niemplatform.canonical.meta.CanonicalTypeResolver;
import gov.niemplatform.contracts.ContractLoader;
import gov.niemplatform.contracts.SchemaHopContract;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Text edits to a contract.
 *
 * <p>Every edit is checked twice: that the file still loads, and that nothing else in it moved. A
 * contract explains itself — the person contract's note about a source switching date formats is
 * worth more than the regex it explains — and a round trip would keep the regex and delete the
 * sentence.
 */
class ContractTextTest {

    private static final Path CONTRACTS = Path.of("").toAbsolutePath().getParent()
            .resolve("modules/law-enforcement/src/main/resources/contracts");

    private String yaml;

    @BeforeEach
    void readTheShippedContract() throws Exception {
        yaml = Files.readString(CONTRACTS.resolve("cad-person-to-canonical-1.0.0.yaml"),
                StandardCharsets.UTF_8);
    }

    private ContractText text() {
        return new ContractText(yaml);
    }

    /** Loads the edited text through the runtime's own loader, as the editor does. */
    private static SchemaHopContract load(String edited) {
        return (SchemaHopContract) new ContractLoader(CanonicalTypeResolver.of(CoreCanonicalTypes.ALL))
                .load(new ByteArrayInputStream(edited.getBytes(StandardCharsets.UTF_8)), "draft");
    }

    private static java.util.List<String> changed(String before, String after) {
        var from = before.lines().toList();
        var to = after.lines().toList();
        var differences = new java.util.ArrayList<String>();
        int shared = Math.min(from.size(), to.size());
        for (int line = 0; line < shared; line++) {
            if (!from.get(line).equals(to.get(line))) {
                differences.add("- " + from.get(line));
                differences.add("+ " + to.get(line));
            }
        }
        return differences;
    }

    @Nested
    @DisplayName("Changing an expectation")
    class Changing {

        @Test
        @DisplayName("rewrites a value that is already there, and nothing else")
        void rewritesInPlace() {
            String edited = text()
                    .setFieldAttribute(ContractText.Side.EXPECTS, "DOB", "pattern",
                            "^\\d{4}-\\d{2}-\\d{2}$")
                    .text();

            assertThat(changed(yaml, edited)).containsExactly(
                    "-       pattern: \"^\\\\d{2}/\\\\d{2}/\\\\d{4}$\"",
                    "+       pattern: \"^\\\\d{4}-\\\\d{2}-\\\\d{2}$\"");
            assertThat(load(edited).expects().field("DOB").orElseThrow().pattern())
                    .isEqualTo("^\\d{4}-\\d{2}-\\d{2}$");
        }

        @Test
        @DisplayName("keeps the reasoning the contract carries")
        void keepsComments() {
            String edited = text()
                    .setFieldAttribute(ContractText.Side.EXPECTS, "SEX", "required", "true").text();

            long before = yaml.lines().filter(line -> line.stripLeading().startsWith("#")).count();
            assertThat(edited.lines().filter(line -> line.stripLeading().startsWith("#")).count())
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("inserts a key the field does not carry yet")
        void insertsAMissingKey() {
            // Marking a field required is the commonest contract edit there is, and most fields do
            // not carry a `required:` line to rewrite.
            String edited = text()
                    .setFieldAttribute(ContractText.Side.EXPECTS, "SEX", "required", "true").text();

            assertThat(edited).contains("    - name: SEX\n      type: string\n      required: true");
            assertThat(load(edited).expects().field("SEX").orElseThrow().required()).isTrue();
        }

        @Test
        @DisplayName("removes the line rather than writing a default")
        void removesRatherThanWritingFalse() {
            // NAME_FULL is required. Clearing it should leave the file saying nothing, not saying
            // `required: false` -- the loader reads them identically and one of them is noise.
            String edited = text()
                    .setFieldAttribute(ContractText.Side.EXPECTS, "NAME_FULL", "required", "false")
                    .text();

            assertThat(edited).doesNotContain("required: false");
            assertThat(edited).contains("    - name: NAME_FULL\n      type: string\n");
            assertThat(load(edited).expects().field("NAME_FULL").orElseThrow().required()).isFalse();
        }

        @Test
        @DisplayName("quotes a pattern so a regex cannot break the file")
        void quotesPatterns() {
            String edited = text()
                    .setFieldAttribute(ContractText.Side.EXPECTS, "SEX", "pattern", "^[MFX]$").text();

            assertThat(edited).contains("pattern: \"^[MFX]$\"");
            assertThat(load(edited).expects().field("SEX").orElseThrow().pattern()).isEqualTo("^[MFX]$");
        }

        @Test
        @DisplayName("refuses to rename a field")
        void refusesRename() {
            // A rename silently detaches the field from every mapping step that reads it, and the
            // mapping is not in front of this class to check.
            assertThatThrownBy(() ->
                    text().setFieldAttribute(ContractText.Side.EXPECTS, "DOB", "name", "BIRTH_DATE"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("add the new field and remove the old one");
        }

        @Test
        @DisplayName("reports a field the side does not declare")
        void refusesAnUnknownField() {
            assertThatThrownBy(() ->
                    text().setFieldAttribute(ContractText.Side.EXPECTS, "NOPE", "required", "true"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("declares no field 'NOPE'");
        }
    }

    @Nested
    @DisplayName("Adding and removing")
    class Structure {

        @Test
        @DisplayName("adds a column the source has started sending")
        void addsAField() {
            String edited = text().addField(ContractText.Side.EXPECTS, "UNIT_ID", "string").text();

            assertThat(edited).contains("    - name: UNIT_ID\n      type: string");
            assertThat(load(edited).expects().field("UNIT_ID")).isPresent();
        }

        @Test
        @DisplayName("refuses a duplicate, which the schema would reject anyway")
        void refusesADuplicate() {
            assertThatThrownBy(() -> text().addField(ContractText.Side.EXPECTS, "DOB", "string"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("may not name a field twice");
        }

        @Test
        @DisplayName("removes a field and the comment explaining it")
        void removesAField() {
            String edited = text().removeField(ContractText.Side.EXPECTS, "DOB").text();

            assertThat(load(edited).expects().field("DOB")).isEmpty();
            assertThat(load(edited).expects().field("NAME_FULL")).isPresent();
        }

        @Test
        @DisplayName("lists what a side declares, in the order the file declares it")
        void listsFields() {
            assertThat(text().fieldNames(ContractText.Side.EXPECTS))
                    .startsWith("NAME_FULL", "DOB", "SEX", "DL_NUM");
        }

        @Test
        @DisplayName("reports an emit side that derives its fields from the canonical model")
        void emitsDerivedFromTheModel() {
            // This contract's emits side is `canonicalType: Person`. There is no field list to
            // edit, and the fields it implies belong to the canonical model, not here.
            assertThat(text().fieldNames(ContractText.Side.EMITS)).isEmpty();
            assertThatThrownBy(() -> text().addField(ContractText.Side.EMITS, "x", "string"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("derives its schema from the canonical model");
        }
    }
}
