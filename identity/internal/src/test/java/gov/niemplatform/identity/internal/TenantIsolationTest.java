package gov.niemplatform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.canonical.meta.TenantId;
import gov.niemplatform.identity.api.ClusterId;
import gov.niemplatform.identity.api.InMemoryClusterIndex;
import gov.niemplatform.identity.api.ResolutionKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Two agencies cannot become one person by accident (ADR 0025).
 *
 * <p>This is the check that decides whether a shared deployment is safe to sell. Before the tenant
 * participated in the seed, two agencies holding the same driver licence number derived the same
 * cluster identifier and their records merged — commingling of criminal justice data between
 * agencies, arriving silently, as a property of a hash function. Nobody would have been notified and
 * no log would have said so.
 *
 * <p>Linking a person across agencies is a deliberate, attributable assertion. It is never a
 * coincidence of hashing, and that distinction is the whole reason federation can be defended to a
 * court.
 */
class TenantIsolationTest {

    private static final TenantId COUNTY = TenantId.of("co.riverton.pd");
    private static final TenantId STATE = TenantId.of("st.colorado.cbi");

    /** The same human, known to both agencies by the same licence number. */
    private static final ResolutionKey SAME_PERSON = new ResolutionKey("DL", "K447-1902");

    /** The same person as an agency's record would present them. */
    private static gov.niemplatform.identity.api.EntityAttributes janeDoe(String sourceKey) {
        return gov.niemplatform.identity.api.EntityAttributes.of("Person", sourceKey, java.util.Map.of(
                "surName", "DOE", "givenName", "JANE", "birthDate", "1988-03-14",
                "driverLicenseId", "K447-1902"));
    }

    @Nested
    @DisplayName("The seed")
    class Seed {

        @Test
        @DisplayName("the same key under two tenants produces two different clusters")
        void doesNotCollideAcrossTenants() {
            ClusterId county = ClusterId.seededBy(COUNTY, "Person", SAME_PERSON);
            ClusterId state = ClusterId.seededBy(STATE, "Person", SAME_PERSON);

            assertThat(county).isNotEqualTo(state);
        }

        @Test
        @DisplayName("the same key under one tenant is still stable, which is what makes replay work")
        void staysDeterministicWithinATenant() {
            // Criterion 6 depends on this: a replay re-resolves from bronze with a fresh index and
            // has to reach the same identities.
            assertThat(ClusterId.seededBy(COUNTY, "Person", SAME_PERSON))
                    .isEqualTo(ClusterId.seededBy(COUNTY, "Person", SAME_PERSON));
        }

        @Test
        @DisplayName("an identifier names the agency that minted it, so it means something elsewhere")
        void carriesItsTenant() {
            // Federation needs an identifier that is meaningful outside the database that made it.
            // A state referring to a county's person cannot do so with an opaque local hash.
            assertThat(ClusterId.seededBy(COUNTY, "Person", SAME_PERSON).value())
                    .startsWith(COUNTY.value() + "/");
        }

        @Test
        @DisplayName("entity type still participates, so a Person and an Incident cannot collide")
        void keepsEntityTypeInTheSeed() {
            assertThat(ClusterId.seededBy(COUNTY, "Person", SAME_PERSON))
                    .isNotEqualTo(ClusterId.seededBy(COUNTY, "Incident", SAME_PERSON));
        }
    }

    @Nested
    @DisplayName("Resolution")
    class Resolution {

        @Test
        @DisplayName("two agencies resolving the same person reach separate identities")
        void resolvesSeparately() {
            var countyIndex = new InMemoryClusterIndex(COUNTY);
            var stateIndex = new InMemoryClusterIndex(STATE);

            ClusterId inCounty = new DeterministicResolutionProvider(countyIndex)
                    .resolve(janeDoe("county-rec-1")).clusterId();
            ClusterId inState = new DeterministicResolutionProvider(stateIndex)
                    .resolve(janeDoe("state-rec-1")).clusterId();

            assertThat(inCounty).isNotEqualTo(inState);
        }

        @Test
        @DisplayName("within one agency the same person still resolves to one cluster")
        void stillResolvesWithinATenant() {
            // Tenant isolation must not break the thing identity resolution is for.
            var index = new InMemoryClusterIndex(COUNTY);
            var resolver = new DeterministicResolutionProvider(index);

            ClusterId first = resolver.resolve(janeDoe("rec-1")).clusterId();
            ClusterId second = resolver.resolve(janeDoe("rec-2")).clusterId();

            assertThat(first).isEqualTo(second);
            assertThat(index.clusterCount("Person")).isEqualTo(1);
        }

        @Test
        @DisplayName("an index knows whose it is, so a boundary is not just a path string")
        void indexNamesItsTenant() {
            assertThat(new InMemoryClusterIndex(COUNTY).tenant()).isEqualTo(COUNTY);
        }
    }

    @Nested
    @DisplayName("Naming a tenant")
    class Naming {

        @Test
        @DisplayName("is constrained, because a tenant becomes a path segment and a table namespace")
        void rejectsUnsafeNames() {
            // A tenant with a slash in it is a path traversal, and discovering that in an agency's
            // environment at deploy time is not the place.
            assertThat(org.assertj.core.api.Assertions
                    .catchThrowableOfType(() -> TenantId.of("../etc"), IllegalArgumentException.class))
                    .isNotNull();
            assertThat(org.assertj.core.api.Assertions
                    .catchThrowableOfType(() -> TenantId.of("two words"), IllegalArgumentException.class))
                    .isNotNull();
        }

        @Test
        @DisplayName("accepts the hierarchy agencies actually use")
        void acceptsRealNames() {
            assertThat(TenantId.of("co.riverton.pd").value()).isEqualTo("co.riverton.pd");
            assertThat(TenantId.of("St.Colorado.CBI").value()).isEqualTo("st.colorado.cbi");
        }

        @Test
        @DisplayName("does not infer authority from the hierarchy, because a prefix is not trust")
        void doesNotInferAuthority() {
            // co.riverton.pd looks like it sits under Colorado. That is a fact about the naming an
            // agency chose, and the platform must not read a trust relationship into it.
            TenantId county = TenantId.of("st.colorado.riverton");
            TenantId state = TenantId.of("st.colorado");

            assertThat(ClusterId.seededBy(county, "Person", SAME_PERSON))
                    .isNotEqualTo(ClusterId.seededBy(state, "Person", SAME_PERSON));
        }
    }
}
