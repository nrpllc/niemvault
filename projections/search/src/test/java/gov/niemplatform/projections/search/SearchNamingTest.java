package gov.niemplatform.projections.search;

import static org.assertj.core.api.Assertions.assertThat;

import gov.niemplatform.canonical.meta.TenantId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SearchNamingTest {

    @Test
    void anAssociationNameBecomesKebabCase() {
        assertThat(SearchNaming.kebab("PersonIncidentAssociation")).isEqualTo("person-incident-association");
        assertThat(SearchNaming.kebab("Person")).isEqualTo("person");
    }

    @Test
    void aQualifiedTypeNameIsNamedByItsSimpleName() {
        // A Record carries ".../core/1.0#Incident"; the alias must not depend on which form arrived.
        assertThat(SearchNaming.kebab("https://niemplatform.gov/canonical/core/1.0#Incident"))
                .isEqualTo("incident");
    }

    @Test
    void theAliasNamesPrefixTenantAndType() {
        assertThat(SearchNaming.alias("niem", TenantId.of("us.fl.leon-so"), "PersonIncidentAssociation"))
                .isEqualTo("niem-us.fl.leon-so-person-incident-association");
    }

    @Test
    void aBackingIndexIsStampedInUtcToTheMillisecond() {
        assertThat(SearchNaming.backingIndex("niem-t-person", Instant.parse("2026-09-29T14:03:07.042Z")))
                .isEqualTo("niem-t-person-20260929140307042");
    }

    @Test
    void theClaimLivesBesideThePrefix() {
        assertThat(SearchNaming.claimIndex("niem")).isEqualTo("niem-deployment");
    }
}
