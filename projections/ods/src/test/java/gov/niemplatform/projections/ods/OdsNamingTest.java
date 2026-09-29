package gov.niemplatform.projections.ods;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OdsNamingTest {

    @Test
    void typesBecomeSnakeCaseTables() {
        assertThat(OdsNaming.table("Person")).isEqualTo("person");
        assertThat(OdsNaming.table("PersonIncidentAssociation")).isEqualTo("person_incident_association");
    }

    @Test
    void fieldsBecomeSnakeCaseColumns() {
        assertThat(OdsNaming.column("reportedDateTime")).isEqualTo("reported_date_time");
        assertThat(OdsNaming.column("beat")).isEqualTo("beat");
        assertThat(OdsNaming.column("obtsID")).isEqualTo("obts_id");
    }

    @Test
    void rolesBecomeIdentityColumns() {
        assertThat(OdsNaming.roleColumn("person")).isEqualTo("person_id");
    }

    @Test
    void objectNamesAreTruncatedToWhatTheServerKeeps() {
        String name = OdsNaming.objectName("fk", "a_very_long_association_table_name_for_testing", "a_long_role_column_id");
        assertThat(name.getBytes(StandardCharsets.UTF_8)).hasSize(OdsNaming.MAX_IDENTIFIER_BYTES);
        assertThat(OdsNaming.objectName("fk", "short", "person_id")).isEqualTo("fk_short_person_id");
    }

    @Test
    void quotingEscapesEmbeddedQuotes() {
        assertThat(OdsNaming.quote("user")).isEqualTo("\"user\"");
        assertThat(OdsNaming.quote("a\"b")).isEqualTo("\"a\"\"b\"");
    }
}
