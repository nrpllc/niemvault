package gov.niemplatform.build.canonical;

import static gov.niemplatform.build.canonical.CanonicalModelTestSupport.CORE_NS;
import static gov.niemplatform.build.canonical.CanonicalModelTestSupport.NIEM_CORE;
import static gov.niemplatform.build.canonical.CanonicalModelTestSupport.assertFailsWith;
import static gov.niemplatform.build.canonical.CanonicalModelTestSupport.load;
import static gov.niemplatform.build.canonical.CanonicalModelTestSupport.write;
import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.build.canonical.CanonicalDslException.Code;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The DSL parser is strict on purpose.
 *
 * <p>A silently ignored key is how a provenance annotation goes missing without anyone
 * noticing -- the exact class of quiet failure the platform exists to catch. So an
 * unrecognised key fails the build rather than warning.
 */
class CanonicalDslParserTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("an unrecognised key at type level is rejected")
    void unknownTypeKey() throws IOException {
        write(dir, "person.yaml", """
                type: Person
                kind: entity
                namespace: "%s"
                version: "1.0.0"
                deprecated: true
                provenance:
                  niemNamespace: "%s"
                  niemType: "nc:PersonType"
                fields:
                  - name: surName
                    type: string
                    provenance:
                      niemNamespace: "%s"
                      niemElement: "nc:PersonSurName"
                """.formatted(CORE_NS, NIEM_CORE, NIEM_CORE));

        assertFailsWith(() -> load(dir), Code.UNKNOWN_KEY);
    }

    @Test
    @DisplayName("an unrecognised key inside a provenance block is rejected")
    void unknownProvenanceKey() throws IOException {
        write(dir, "person.yaml", """
                type: Person
                kind: entity
                namespace: "%s"
                version: "1.0.0"
                provenance:
                  niemNamespace: "%s"
                  niemType: "nc:PersonType"
                  niemVersion: "6.0"
                fields:
                  - name: surName
                    type: string
                    provenance:
                      niemNamespace: "%s"
                      niemElement: "nc:PersonSurName"
                """.formatted(CORE_NS, NIEM_CORE, NIEM_CORE));

        assertFailsWith(() -> load(dir), Code.UNKNOWN_KEY);
    }

    @Test
    @DisplayName("a duplicate key in one document is rejected rather than last-wins")
    void duplicateKey() throws IOException {
        write(dir, "person.yaml", """
                type: Person
                kind: entity
                namespace: "%s"
                version: "1.0.0"
                version: "2.0.0"
                provenance:
                  niemNamespace: "%s"
                  niemType: "nc:PersonType"
                fields:
                  - name: surName
                    type: string
                    provenance:
                      niemNamespace: "%s"
                      niemElement: "nc:PersonSurName"
                """.formatted(CORE_NS, NIEM_CORE, NIEM_CORE));

        assertFailsWith(() -> load(dir), Code.MALFORMED_YAML);
    }

    @Test
    @DisplayName("an empty document is rejected, not silently skipped")
    void emptyDocument() throws IOException {
        write(dir, "empty.yaml", "# nothing here yet\n");

        assertFailsWith(() -> load(dir), Code.MALFORMED_YAML);
    }

    @Test
    @DisplayName("problems are attributed to the file that contains them")
    void problemsCarryTheirFile() throws IOException {
        write(dir, "good.yaml", CanonicalModelTestSupport.validPerson());
        write(dir, "bad.yaml", """
                type: Bad
                kind: entity
                namespace: "%s"
                version: "1.0.0"
                """.formatted(CORE_NS));

        try {
            load(dir);
        } catch (CanonicalDslException e) {
            assertThat(e.problems())
                    .allSatisfy(p -> assertThat(p.file().getFileName()).hasToString("bad.yaml"));
            return;
        }
        throw new AssertionError("expected bad.yaml to be rejected");
    }
}
