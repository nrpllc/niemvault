package gov.niemplatform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.identity.api.ClusterId;
import gov.niemplatform.identity.api.ClusterIndex;
import gov.niemplatform.identity.api.EntityAttributes;
import gov.niemplatform.identity.api.InMemoryClusterIndex;
import gov.niemplatform.identity.api.MatchEvidence;
import gov.niemplatform.identity.api.ResolutionProvider;
import gov.niemplatform.identity.api.ResolutionResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The bundled deterministic resolver (ADR 0014).
 *
 * <p>Acceptance criterion 3 lives here: two source records for the same human must resolve to one
 * cluster. So does the property that matters more commercially and is easier to lose -- that two
 * records for <em>different</em> humans must not.
 */
class DeterministicResolutionProviderTest {

    private ClusterIndex index;
    private DeterministicResolutionProvider resolver;

    @BeforeEach
    void setUp() {
        index = new InMemoryClusterIndex(gov.niemplatform.canonical.meta.TenantId.of("test.agency"));
        resolver = new DeterministicResolutionProvider(index);
    }

    private ResolutionResult resolve(String sourceKey, Map<String, String> attributes) {
        return resolver.resolve(EntityAttributes.of("Person", sourceKey, attributes));
    }

    private static Map<String, String> person(String... pairs) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            attributes.put(pairs[i], pairs[i + 1]);
        }
        return attributes;
    }

    @Nested
    @DisplayName("criterion 3: the same human resolves to one cluster")
    class SameHuman {

        @Test
        @DisplayName("a licence written two ways is one person")
        void licencePunctuationDoesNotSplit() {
            ClusterId first = resolve("rec-1", person(
                    "surName", "DOE", "givenName", "JANE", "birthDate", "1988-03-14",
                    "driverLicenseId", "K447-1902")).clusterId();

            ResolutionResult again = resolve("rec-2", person(
                    "surName", "DOE", "givenName", "JANE", "birthDate", "1988-03-14",
                    "driverLicenseId", "k4471902"));

            assertThat(again.clusterId()).isEqualTo(first);
            assertThat(again.newCluster()).isFalse();
            assertThat(again.confidence()).isEqualTo(0.99);
        }

        @Test
        @DisplayName("a later record with only a name and date of birth reaches a licence-seeded cluster")
        void weakerKeyReachesExistingCluster() {
            ClusterId first = resolve("rec-1", person(
                    "surName", "DOE", "givenName", "JANE", "birthDate", "1988-03-14",
                    "driverLicenseId", "K4471902")).clusterId();

            ResolutionResult nameOnly = resolve("rec-2", person(
                    "surName", "Doe", "givenName", "Jane", "birthDate", "1988-03-14"));

            assertThat(nameOnly.clusterId()).isEqualTo(first);
            assertThat(nameOnly.confidence()).isEqualTo(0.90);
        }

        @Test
        @DisplayName("a family name written with and without an apostrophe is one person")
        void punctuationInNames() {
            ClusterId first = resolve("rec-1", person(
                    "surName", "O'BRIEN", "givenName", "SEAN", "birthDate", "1970-01-05")).clusterId();

            assertThat(resolve("rec-2", person(
                    "surName", "OBRIEN", "givenName", "Sean", "birthDate", "1970-01-05")).clusterId())
                    .isEqualTo(first);
        }

        @Test
        @DisplayName("a Social Security Number matches on its own tier")
        void ssnTier() {
            ClusterId first = resolve("rec-1", person(
                    "surName", "DOE", "birthDate", "1988-03-14",
                    "socialSecurityId", "123-45-6789")).clusterId();

            ResolutionResult again = resolve("rec-2", person(
                    "surName", "DIFFERENT", "birthDate", "1990-01-01",
                    "socialSecurityId", "123456789"));

            assertThat(again.clusterId()).isEqualTo(first);
            assertThat(again.confidence()).isEqualTo(0.99);
        }
    }

    @Nested
    @DisplayName("different humans stay apart")
    class DifferentHumans {

        @Test
        @DisplayName("a different person gets a different cluster")
        void differentPersonSeparate() {
            ClusterId first = resolve("rec-1", person(
                    "surName", "DOE", "givenName", "JANE", "birthDate", "1988-03-14")).clusterId();

            assertThat(resolve("rec-2", person(
                    "surName", "RIVERA", "givenName", "LUIS", "birthDate", "1975-11-02")).clusterId())
                    .isNotEqualTo(first);
        }

        @Test
        @DisplayName("the same name with a different date of birth is a different person")
        void sameNameDifferentBirthDate() {
            ClusterId first = resolve("rec-1", person(
                    "surName", "SMITH", "givenName", "JOHN", "birthDate", "1980-01-01")).clusterId();

            assertThat(resolve("rec-2", person(
                    "surName", "SMITH", "givenName", "JOHN", "birthDate", "1991-06-30")).clusterId())
                    .isNotEqualTo(first);
        }

        @Test
        @DisplayName("the resolver under-matches rather than risk a false merge")
        void underMatchesOnNameVariants() {
            ClusterId first = resolve("rec-1", person(
                    "surName", "SMITH", "givenName", "JON", "birthDate", "1980-01-01")).clusterId();

            assertThat(resolve("rec-2", person(
                    "surName", "SMITH", "givenName", "JONATHAN", "birthDate", "1980-01-01")).clusterId())
                    .as("a documented, permanent limitation of deterministic rules -- ADR 0014")
                    .isNotEqualTo(first);
        }

        @Test
        @DisplayName("a stronger tier wins, and the conflicting weaker key is left alone")
        void strongerTierWinsWithoutMerging() {
            // Two people already exist: one known by licence, one by name and date of birth.
            ClusterId byLicence = resolve("rec-1", person(
                    "driverLicenseId", "AAA111")).clusterId();
            ClusterId byName = resolve("rec-2", person(
                    "surName", "DOE", "givenName", "JANE", "birthDate", "1988-03-14")).clusterId();
            assertThat(byLicence).isNotEqualTo(byName);

            // A record carrying both keys must not silently merge them.
            ResolutionResult conflicted = resolve("rec-3", person(
                    "driverLicenseId", "AAA111",
                    "surName", "DOE", "givenName", "JANE", "birthDate", "1988-03-14"));

            assertThat(conflicted.clusterId()).isEqualTo(byLicence);
            assertThat(conflicted.evidence()).extracting(MatchEvidence::rule)
                    .contains("TIER_CONFLICT");
            assertThat(index.clusterCount("Person")).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("evidence makes a decision explicable")
    class Evidence {

        @Test
        @DisplayName("a match names the rule and the fields it matched on")
        void matchEvidence() {
            resolve("rec-1", person("driverLicenseId", "K4471902"));

            ResolutionResult matched = resolve("rec-2", person("driverLicenseId", "K447-1902"));

            assertThat(matched.evidence()).first()
                    .returns("TIER_DL", MatchEvidence::rule)
                    .satisfies(evidence ->
                            assertThat(evidence.matchedFields()).contains("driverLicenseId"));
        }

        @Test
        @DisplayName("a new cluster says why nothing matched")
        void newClusterEvidence() {
            ResolutionResult created = resolve("rec-1", person("driverLicenseId", "K4471902"));

            assertThat(created.newCluster()).isTrue();
            assertThat(created.evidence()).first().returns("NEW_CLUSTER", MatchEvidence::rule);
        }

        @Test
        @DisplayName("evidence never quotes the values it matched on")
        void evidenceRedactsValues() {
            resolve("rec-1", person("driverLicenseId", "K4471902"));

            String rendered = resolve("rec-2", person("driverLicenseId", "K4471902"))
                    .evidence().toString();

            assertThat(rendered).doesNotContain("K4471902").contains("DL");
        }
    }

    @Nested
    @DisplayName("records the resolver cannot match")
    class Unmatchable {

        @Test
        @DisplayName("a record with no identity-bearing attribute still gets a stable identity")
        void noAttributesStillResolves() {
            ResolutionResult isolated = resolve("rec-1", person("sexCode", "F"));

            assertThat(isolated.newCluster()).isTrue();
            assertThat(isolated.clusterId()).isNotNull();
            assertThat(isolated.evidence()).first()
                    .satisfies(evidence -> assertThat(evidence.detail())
                            .contains("no identity-bearing attribute"));
        }

        @Test
        @DisplayName("two attribute-less records are not merged with each other")
        void attributelessRecordsStaySeparate() {
            ClusterId first = resolve("rec-1", person("sexCode", "F")).clusterId();

            assertThat(resolve("rec-2", person("sexCode", "F")).clusterId()).isNotEqualTo(first);
        }

        @Test
        @DisplayName("a surname with no date of birth is not enough to match on")
        void surnameAloneIsNotAKey() {
            ClusterId first = resolve("rec-1", person("surName", "DOE")).clusterId();

            assertThat(resolve("rec-2", person("surName", "DOE")).clusterId()).isNotEqualTo(first);
        }
    }

    @Test
    @DisplayName("the same records in the same order always produce the same clusters")
    void resolutionIsDeterministic() {
        DeterministicResolutionProvider other =
                new DeterministicResolutionProvider(new InMemoryClusterIndex(gov.niemplatform.canonical.meta.TenantId.of("test.agency")));

        ClusterId first = resolve("rec-1", person(
                "surName", "DOE", "givenName", "JANE", "birthDate", "1988-03-14",
                "driverLicenseId", "K4471902")).clusterId();
        ClusterId replayed = other.resolve(EntityAttributes.of("Person", "rec-1", person(
                "surName", "DOE", "givenName", "JANE", "birthDate", "1988-03-14",
                "driverLicenseId", "K4471902"))).clusterId();

        assertThat(replayed)
                .as("acceptance criterion 6 depends on this")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("an unsupported entity type is refused rather than guessed at")
    void unsupportedEntityType() {
        assertThatThrownBy(() -> resolver.resolve(
                EntityAttributes.of("Vehicle", "rec-1", person("vin", "1HGCM"))))
                .isInstanceOf(ResolutionProvider.UnsupportedEntityTypeException.class);
    }

    @Test
    @DisplayName("capabilities declare the resolver is not probabilistic")
    void capabilitiesAreHonest() {
        assertThat(resolver.capabilities())
                .returns("bundled-deterministic", caps -> caps.providerId())
                .returns(false, caps -> caps.probabilistic())
                .satisfies(caps -> assertThat(caps.supports("Person")).isTrue())
                .satisfies(caps -> assertThat(caps.confidenceTiers()).containsExactly(0.99, 0.90, 1.0));
    }
}
