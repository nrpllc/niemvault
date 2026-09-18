package gov.niemplatform.exchange.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.canonical.core.CoreCanonicalTypes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An exchange is configuration, and wrong configuration is caught before anything is sent
 * (ADR 0010, ADR 0034).
 *
 * <p>The failure this guards against is quiet rather than loud. A misspelled role, or an
 * association followed from a type it does not touch, assembles <em>nothing</em> -- and a
 * repository accepts a document whose optional elements are absent without complaint. The
 * submission succeeds, the run reports success, and nobody finds out until someone asks why no
 * charge has ever had a disposition.
 */
class AnAssemblyIsCheckedAgainstTheModelTest {

    @TempDir
    Path dir;

    /**
     * The real criminal history shape: an arrest, who it was, the counts, and how each count ended.
     *
     * <p>Worth stating plainly because it is the whole point of the exercise -- this is the
     * document the previous writer could not produce at all, expressed without a line of Java.
     */
    @Test
    @DisplayName("a criminal history submission assembles from configuration alone")
    void theCchShapeLoads() throws IOException {
        ExchangeDefinition exchange = load("""
                exchange: fdle-cch-arrest
                version: "1.0.0"
                type: cch-http
                sourceId: leon-so-cad

                assemble:
                  root: Arrest
                  follow:
                    - association: ArrestSubjectAssociation
                      role: person
                      as: subject
                    - association: BookingArrestAssociation
                      role: booking
                      as: booking
                    - association: ArrestChargeAssociation
                      role: charge
                      as: charges
                      follow:
                        - association: ChargeDispositionAssociation
                          role: disposition
                          as: disposition
                        - association: ChargeSentenceAssociation
                          role: sentence
                          as: sentence

                settings:
                  endpoint: https://cch.fdle.example/submit
                """);

        assertThat(exchange.exchangeName()).isEqualTo("fdle-cch-arrest");
        assertThat(exchange.type()).isEqualTo(ExchangeType.of("cch-http"));
        assertThat(exchange.assemble().rootType()).isEqualTo("Arrest");
        assertThat(exchange.assemble().typesTouched(CoreCanonicalTypes.ALL))
                .contains("Arrest", "Person", "Booking", "Charge", "Disposition", "Sentence");
    }

    /**
     * Adding a field to a submission must not be a platform release.
     *
     * <p>Two exchanges over one model, differing only in what they gather. Under the previous
     * writer this was two code paths; here it is two files.
     */
    @Test
    @DisplayName("a narrower submission is a different file, not a different build")
    void aSecondShapeIsJustAnotherFile() throws IOException {
        ExchangeDefinition minimal = load("""
                exchange: fdle-cch-arrest-minimal
                version: "1.0.0"
                type: cch-http
                sourceId: leon-so-cad
                assemble:
                  root: Arrest
                  follow:
                    - association: ArrestSubjectAssociation
                      role: person
                """);

        assertThat(minimal.assemble().typesTouched(CoreCanonicalTypes.ALL))
                .containsExactly("Arrest", "ArrestSubjectAssociation", "Person");
        // 'as' defaults to the role name, so the common case carries no boilerplate.
        assertThat(minimal.assemble().follow().getFirst().as()).isEqualTo("person");
    }

    /**
     * The check that earns this class its keep.
     *
     * <p>Following ChargeDispositionAssociation from an Arrest reads perfectly well and is wrong:
     * the association touches Charge, not Arrest. Unvalidated it assembles nothing, and the
     * submission still succeeds.
     */
    @Test
    @DisplayName("an association followed from a type it does not touch is refused")
    void anUnanchoredFollowIsRefused() {
        assertThatThrownBy(() -> load("""
                exchange: wrong
                version: "1.0.0"
                type: cch-http
                sourceId: leon-so-cad
                assemble:
                  root: Arrest
                  follow:
                    - association: ChargeDispositionAssociation
                      role: disposition
                """))
                .isInstanceOf(ExchangeDefinitionException.class)
                .hasMessageContaining("does not connect to 'Arrest'")
                .hasMessageContaining("would assemble nothing");
    }

    @Test
    @DisplayName("a misspelled role is refused, and told which roles exist")
    void aMisspelledRoleIsRefused() {
        assertThatThrownBy(() -> load("""
                exchange: wrong
                version: "1.0.0"
                type: cch-http
                sourceId: leon-so-cad
                assemble:
                  root: Arrest
                  follow:
                    - association: ArrestSubjectAssociation
                      role: subject
                """))
                .isInstanceOf(ExchangeDefinitionException.class)
                .hasMessageContaining("has no role 'subject'")
                .hasMessageContaining("[arrest, person]");
    }

    @Test
    @DisplayName("an entity cannot be followed, and an association cannot be a root")
    void kindsAreChecked() {
        assertThatThrownBy(() -> load("""
                exchange: wrong
                version: "1.0.0"
                type: cch-http
                sourceId: leon-so-cad
                assemble:
                  root: Arrest
                  follow:
                    - association: Charge
                      role: charge
                """))
                .isInstanceOf(ExchangeDefinitionException.class)
                .hasMessageContaining("is an entity, not an association");

        assertThatThrownBy(() -> load("""
                exchange: wrong
                version: "1.0.0"
                type: cch-http
                sourceId: leon-so-cad
                assemble:
                  root: ArrestChargeAssociation
                """))
                .isInstanceOf(ExchangeDefinitionException.class)
                .hasMessageContaining("is an association");
    }

    /** A document that contains itself does not terminate, and the loader says so rather than hanging. */
    @Test
    @DisplayName("a cycle in the walk is refused rather than assembled")
    void aCycleIsRefused() {
        assertThatThrownBy(() -> load("""
                exchange: wrong
                version: "1.0.0"
                type: cch-http
                sourceId: leon-so-cad
                assemble:
                  root: Arrest
                  follow:
                    - association: ArrestChargeAssociation
                      role: charge
                      follow:
                        - association: ArrestChargeAssociation
                          role: arrest
                """))
                .isInstanceOf(ExchangeDefinitionException.class)
                .hasMessageContaining("already in this branch")
                .hasMessageContaining("does not terminate");
    }

    @Test
    @DisplayName("two elements of one name at one level would overwrite each other, so they are refused")
    void duplicateElementNamesAreRefused() {
        assertThatThrownBy(() -> load("""
                exchange: wrong
                version: "1.0.0"
                type: cch-http
                sourceId: leon-so-cad
                assemble:
                  root: Arrest
                  follow:
                    - association: ArrestSubjectAssociation
                      role: person
                      as: party
                    - association: ArrestChargeAssociation
                      role: charge
                      as: party
                """))
                .isInstanceOf(ExchangeDefinitionException.class)
                .hasMessageContaining("used twice");
    }

    @Test
    @DisplayName("an unknown canonical type names what this deployment carries")
    void unknownTypesAreNamed() {
        assertThatThrownBy(() -> load("""
                exchange: wrong
                version: "1.0.0"
                type: cch-http
                sourceId: leon-so-cad
                assemble:
                  root: Warrant
                """))
                .isInstanceOf(ExchangeDefinitionException.class)
                .hasMessageContaining("not a canonical type")
                .hasMessageContaining("Arrest");
    }

    /** ADR 0010, and the reason it applies here: a silently ignored key submits an empty document. */
    @Test
    @DisplayName("a misspelled key is an error at every level, not just the top")
    void unknownKeysAreRefused() {
        assertThatThrownBy(() -> load("""
                exchange: wrong
                version: "1.0.0"
                type: cch-http
                sourceId: leon-so-cad
                asemble:
                  root: Arrest
                """))
                .isInstanceOf(ExchangeDefinitionException.class)
                .hasMessageContaining("asemble");

        assertThatThrownBy(() -> load("""
                exchange: wrong
                version: "1.0.0"
                type: cch-http
                sourceId: leon-so-cad
                assemble:
                  root: Arrest
                  follow:
                    - association: ArrestSubjectAssociation
                      rolle: person
                """))
                .isInstanceOf(ExchangeDefinitionException.class)
                .hasMessageContaining("rolle");
    }

    @Test
    @DisplayName("an exchange with no assembly is refused, because an empty document is accepted")
    void assemblyIsRequired() {
        assertThatThrownBy(() -> load("""
                exchange: wrong
                version: "1.0.0"
                type: cch-http
                sourceId: leon-so-cad
                """))
                .isInstanceOf(ExchangeDefinitionException.class)
                .hasMessageContaining("sends an empty document");
    }

    @Test
    @DisplayName("every problem is reported at once, so an author fixes them in one pass")
    void allProblemsAtOnce() {
        assertThatThrownBy(() -> load("""
                exchange: wrong
                type: NotKebab
                sourceId: leon-so-cad
                assemble:
                  root: Arrest
                  follow:
                    - association: ArrestSubjectAssociation
                      role: nope
                """))
                .isInstanceOf(ExchangeDefinitionException.class)
                .hasMessageContaining("'version' is required")
                .hasMessageContaining("kebab-case")
                .hasMessageContaining("has no role 'nope'");
    }

    @Test
    @DisplayName("a definition prints setting keys and never values")
    void printsNoSecret() throws IOException {
        ExchangeDefinition exchange = load("""
                exchange: fdle-cch-arrest
                version: "1.0.0"
                type: cch-http
                sourceId: leon-so-cad
                assemble:
                  root: Arrest
                settings:
                  endpoint: https://cch.fdle.example/submit
                  token: super-secret-value
                """);

        assertThat(exchange.toString())
                .contains("token")
                .doesNotContain("super-secret-value");
    }

    private ExchangeDefinition load(String yaml) throws IOException {
        Path file = Files.createTempFile(dir, "exchange", ".yaml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return ExchangeDefinition.load(file, CoreCanonicalTypes.ALL);
    }
}
