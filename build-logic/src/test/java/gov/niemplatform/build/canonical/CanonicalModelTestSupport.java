package gov.niemplatform.build.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/** Shared fixtures for canonical DSL tests. */
final class CanonicalModelTestSupport {

    static final String CORE_NS = "https://niemplatform.gov/canonical/core/1.0";
    static final String EXT_NS = "https://niemplatform.gov/canonical/extension/core/1.0";
    static final String EXT_ROOT = "https://niemplatform.gov/canonical/extension/";
    static final String NIEM_CORE = "https://docs.oasis-open.org/niemopen/ns/model/niem-core/6.0/";

    private CanonicalModelTestSupport() {}

    /** Writes a DSL document into {@code dir} and returns the directory, for parsing. */
    static Path write(Path dir, String fileName, String yaml) throws IOException {
        Files.writeString(dir.resolve(fileName), yaml, StandardCharsets.UTF_8);
        return dir;
    }

    /** Parses and validates every document under {@code dir}. */
    static List<TypeDef> load(Path dir) {
        List<TypeDef> types = new CanonicalDslParser().parseAll(List.of(dir));
        new CanonicalModelValidator(EXT_ROOT).validate(types);
        return types;
    }

    /** Asserts the action fails with at least one problem carrying the expected code. */
    static void assertFailsWith(ThrowingCallable action, CanonicalDslException.Code expected) {
        assertThatThrownBy(action)
                .isInstanceOf(CanonicalDslException.class)
                .satisfies(thrown -> {
                    List<CanonicalDslException.Problem> problems =
                            ((CanonicalDslException) thrown).problems();
                    assertThat(problems)
                            .as("problems reported: %s", problems)
                            .extracting(CanonicalDslException.Problem::code)
                            .contains(expected);
                });
    }

    /** A minimal valid NIEM-sourced entity. */
    static String validPerson() {
        return """
                type: Person
                kind: entity
                namespace: "%s"
                version: "1.0.0"
                provenance:
                  niemNamespace: "%s"
                  niemType: "nc:PersonType"
                fields:
                  - name: surName
                    type: string
                    required: true
                    provenance:
                      niemNamespace: "%s"
                      niemElement: "nc:PersonSurName"
                """.formatted(CORE_NS, NIEM_CORE, NIEM_CORE);
    }

    /** A minimal valid NIEM-sourced entity, usable as an association target. */
    static String validIncident() {
        return """
                type: Incident
                kind: entity
                namespace: "%s"
                version: "1.0.0"
                provenance:
                  niemNamespace: "%s"
                  niemType: "nc:IncidentType"
                fields:
                  - name: incidentNumber
                    type: string
                    required: true
                    provenance:
                      niemNamespace: "%s"
                      niemElement: "nc:ActivityIdentification"
                """.formatted(CORE_NS, NIEM_CORE, NIEM_CORE);
    }
}
