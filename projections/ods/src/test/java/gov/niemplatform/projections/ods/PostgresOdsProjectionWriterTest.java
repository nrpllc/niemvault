package gov.niemplatform.projections.ods;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalFieldDescriptor;
import gov.niemplatform.canonical.meta.CanonicalId;
import gov.niemplatform.canonical.meta.CanonicalKind;
import gov.niemplatform.canonical.meta.CanonicalRef;
import gov.niemplatform.canonical.meta.CanonicalRoleDescriptor;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.canonical.meta.ExtensionJustification;
import gov.niemplatform.canonical.meta.FieldType;
import gov.niemplatform.canonical.meta.NiemProvenance;
import gov.niemplatform.canonical.meta.TenantId;
import gov.niemplatform.projections.api.CanonicalChangeSet;
import gov.niemplatform.projections.api.CanonicalSnapshot;
import gov.niemplatform.projections.api.ProjectionContext;
import gov.niemplatform.projections.api.ProjectionDefinition;
import gov.niemplatform.projections.api.ProjectionDefinitionException;
import gov.niemplatform.projections.api.ProjectionException;
import gov.niemplatform.projections.api.ProjectionException.Operation;
import gov.niemplatform.projections.api.ProjectionRegistry;
import gov.niemplatform.projections.api.ProjectionType;
import gov.niemplatform.projections.api.ProjectionWriter;
import gov.niemplatform.projections.api.TypedRecords;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The ODS against a real PostgreSQL.
 *
 * <p>Not a fake, for the reason the graph writer's test gives: everything worth testing here --
 * deferred foreign keys failing at commit, {@code TRUNCATE} refusing a table another schema
 * references, upsert-with-coalesce semantics -- is the database's behaviour, and a mock would only
 * restate what the writer believes about it.
 */
@Tag("docker")
@Testcontainers
class PostgresOdsProjectionWriterTest {

    private static final String NIEM_CORE =
            "https://docs.oasis-open.org/niemopen/ns/model/niem-core/6.0/";
    private static final TenantId LEON = TenantId.of("us.fl.leon-so");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    private final List<ProjectionWriter> opened = new ArrayList<>();

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS ods_workflow CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS canonical CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS niem_meta CASCADE");
        }
    }

    @AfterEach
    void closeWriters() {
        opened.forEach(ProjectionWriter::close);
    }

    // --- model -----------------------------------------------------------

    private static CanonicalTypeDescriptor personType(CanonicalFieldDescriptor... extra) {
        List<CanonicalFieldDescriptor> fields = new ArrayList<>(List.of(
                niemField("surName", FieldType.STRING),
                niemField("givenName", FieldType.STRING),
                niemField("birthDate", FieldType.DATE)));
        fields.addAll(List.of(extra));
        return new CanonicalTypeDescriptor("Person",
                "https://niemplatform.gov/canonical/core/1.0", "1.0.0", CanonicalKind.ENTITY,
                new NiemProvenance(NIEM_CORE, "nc:PersonType", null), null, fields, List.of());
    }

    private static CanonicalTypeDescriptor incidentType() {
        return new CanonicalTypeDescriptor("Incident",
                "https://niemplatform.gov/canonical/core/1.0", "1.0.0", CanonicalKind.ENTITY,
                new NiemProvenance(NIEM_CORE, "nc:IncidentType", null), null,
                List.of(
                        niemField("incidentNumber", FieldType.STRING),
                        niemField("reportedDateTime", FieldType.DATE_TIME),
                        new CanonicalFieldDescriptor("beat", FieldType.STRING, false, false, null,
                                new ExtensionJustification("Agency's own patrol geography."), List.of(), null)),
                List.of());
    }

    private static CanonicalTypeDescriptor associationType() {
        return new CanonicalTypeDescriptor("PersonIncidentAssociation",
                "https://niemplatform.gov/canonical/core/1.0", "1.0.0",
                CanonicalKind.ASSOCIATION,
                new NiemProvenance(NIEM_CORE, "nc:ActivityPersonAssociationType", null), null,
                List.of(new CanonicalFieldDescriptor("involvementCode", FieldType.CODE, true, false,
                        new NiemProvenance(NIEM_CORE, null, "nc:ActivityInvolvementAbstract"), null,
                        List.of("VICTIM", "SUSPECT", "WITNESS"), null)),
                List.of(
                        new CanonicalRoleDescriptor("person", "Person",
                                new NiemProvenance(NIEM_CORE, null, "nc:Person"), null),
                        new CanonicalRoleDescriptor("incident", "Incident",
                                new NiemProvenance(NIEM_CORE, null, "nc:Activity"), null)));
    }

    private static CanonicalFieldDescriptor niemField(String name, FieldType type) {
        return new CanonicalFieldDescriptor(name, type, false, false,
                new NiemProvenance(NIEM_CORE, null, "nc:" + name), null, List.of(), null);
    }

    private static List<CanonicalTypeDescriptor> model() {
        return List.of(personType(), incidentType(), associationType());
    }

    private static Record person(String id, String surname, LocalDate birthDate) {
        Record.Builder builder = Record.builder(personType().qualifiedName())
                .set("canonicalId", CanonicalId.of(id))
                .set("surName", surname)
                .set("givenName", "Alex");
        if (birthDate != null) {
            builder.set("birthDate", birthDate);
        }
        return builder.build();
    }

    private static Record incident(String id, String number) {
        return Record.builder(incidentType().qualifiedName())
                .set("canonicalId", CanonicalId.of(id))
                .set("incidentNumber", number)
                .set("reportedDateTime", Instant.parse("2026-03-04T18:20:00Z"))
                .set("beat", "B4")
                .build();
    }

    private static Record association(String id, String personId, String incidentId, String role) {
        return Record.builder(associationType().qualifiedName())
                .set("canonicalId", CanonicalId.of(id))
                .set("person", CanonicalRef.to("Person", personId))
                .set("incident", CanonicalRef.to("Incident", incidentId))
                .set("involvementCode", role)
                .build();
    }

    private static CanonicalChangeSet changes(String runId, List<Record> persons,
            List<Record> incidents, List<Record> associations) {
        return new CanonicalChangeSet(runId, List.of(
                new TypedRecords(personType(), persons),
                new TypedRecords(incidentType(), incidents),
                new TypedRecords(associationType(), associations)));
    }

    private static CanonicalChangeSet oneIncident(String runId) {
        return changes(runId,
                List.of(person("p-1", "Rivera", LocalDate.of(1988, 3, 14))),
                List.of(incident("i-1", "LCSO-26-0001")),
                List.of(association("a-1", "p-1", "i-1", "SUSPECT")));
    }

    // --- harness ---------------------------------------------------------

    private static ProjectionContext context(TenantId tenant, List<CanonicalTypeDescriptor> model) {
        return new ProjectionContext(tenant, "leon-so-cad", "leon-cad-to-canonical", "1.0.0",
                model, Map.of("NIEM_ODS_PASSWORD", POSTGRES.getPassword())::get);
    }

    private PostgresOdsProjectionWriter open(TenantId tenant, List<CanonicalTypeDescriptor> model) {
        PostgresOdsProjectionWriter writer = new PostgresOdsProjectionWriter(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword(), "canonical", context(tenant, model));
        opened.add(writer);
        return writer;
    }

    private PostgresOdsProjectionWriter open() {
        return open(LEON, model());
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Object single(String sql) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).as("a row from: %s", sql).isTrue();
            return rows.getObject(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    // --- apply -----------------------------------------------------------

    @Test
    void applyCreatesATablePerTypeAndWritesTheRecords() throws SQLException {
        PostgresOdsProjectionWriter writer = open();

        writer.apply(oneIncident("run-1"));

        assertThat(writer.count(personType())).isEqualTo(1);
        assertThat(writer.count(incidentType())).isEqualTo(1);
        assertThat(writer.count(associationType())).isEqualTo(1);
        assertThat(single("SELECT sur_name FROM canonical.person WHERE canonical_id = 'p-1'"))
                .isEqualTo("Rivera");
        assertThat(single("SELECT birth_date FROM canonical.person")).isEqualTo(java.sql.Date.valueOf("1988-03-14"));
        assertThat(single("SELECT reported_date_time = '2026-03-04T18:20:00Z'::timestamptz FROM canonical.incident"))
                .isEqualTo(true);
        assertThat(single("SELECT person_id || '>' || incident_id || ':' || involvement_code "
                + "FROM canonical.person_incident_association")).isEqualTo("p-1>i-1:SUSPECT");
        assertThat(single("SELECT niem_run_id || ' ' || niem_source_id || ' ' || niem_mapping "
                + "FROM canonical.incident"))
                .isEqualTo("run-1 leon-so-cad leon-cad-to-canonical@1.0.0");
    }

    @Test
    void columnsCarryTheirNiemProvenance() throws SQLException {
        open();

        assertThat((String) single("SELECT obj_description('canonical.person'::regclass, 'pg_class')"))
                .contains("nc:PersonType");
        assertThat((String) single("SELECT col_description('canonical.incident'::regclass, "
                + "(SELECT attnum FROM pg_attribute WHERE attrelid = 'canonical.incident'::regclass "
                + "AND attname = 'beat'))"))
                .contains("Extension to NIEM").contains("patrol geography");
    }

    @Test
    void applyingTheSameChangeSetTwiceLeavesTheSameTables() {
        PostgresOdsProjectionWriter writer = open();

        writer.apply(oneIncident("run-1"));
        writer.apply(oneIncident("run-1"));

        assertThat(writer.count(personType())).isEqualTo(1);
        assertThat(writer.count(incidentType())).isEqualTo(1);
        assertThat(writer.count(associationType())).isEqualTo(1);
    }

    @Test
    void anAbsentValueDoesNotEraseAPresentOne() throws SQLException {
        PostgresOdsProjectionWriter writer = open();
        writer.apply(oneIncident("run-1"));

        writer.apply(changes("run-2",
                List.of(person("p-1", "Rivera-Ortiz", null)), List.of(), List.of()));

        assertThat(single("SELECT sur_name FROM canonical.person")).isEqualTo("Rivera-Ortiz");
        assertThat(single("SELECT birth_date FROM canonical.person"))
                .as("a later record without a birth date must not clear the earlier one")
                .isEqualTo(java.sql.Date.valueOf("1988-03-14"));
        assertThat(single("SELECT niem_run_id FROM canonical.person")).isEqualTo("run-2");
    }

    @Test
    void anAssociationToAMissingEntityFailsTheWholeChangeSet() {
        PostgresOdsProjectionWriter writer = open();

        assertThatThrownBy(() -> writer.apply(changes("run-1",
                List.of(person("p-1", "Rivera", null)),
                List.of(),
                List.of(association("a-1", "p-1", "i-missing", "WITNESS")))))
                .isInstanceOf(ProjectionException.class)
                .hasMessageContaining("references an entity the ODS does not hold")
                .extracting(e -> ((ProjectionException) e).operation())
                .isEqualTo(Operation.APPLY);

        assertThat(writer.count(personType()))
                .as("the person arrived in the same change set and must not be half-written")
                .isZero();
        assertThat(writer.count(associationType())).isZero();
    }

    @Test
    void recordsOfTheSameChangeSetMayArriveInAnyOrder() {
        PostgresOdsProjectionWriter writer = open();

        // Associations before entities: the foreign key is deferred to commit.
        writer.apply(new CanonicalChangeSet("run-1", List.of(
                new TypedRecords(associationType(), List.of(association("a-1", "p-1", "i-1", "VICTIM"))),
                new TypedRecords(personType(), List.of(person("p-1", "Rivera", null))),
                new TypedRecords(incidentType(), List.of(incident("i-1", "LCSO-26-0001"))))));

        assertThat(writer.count(associationType())).isEqualTo(1);
    }

    @Test
    void theLedgerRecordsEveryApplyAndRebuild() throws SQLException {
        PostgresOdsProjectionWriter writer = open();
        writer.apply(oneIncident("run-1"));
        writer.apply(oneIncident("run-1"));
        writer.rebuild(new CanonicalSnapshot(oneIncident("x").changes()));

        assertThat(((Number) single("SELECT count(*) FROM niem_meta.projection_run "
                + "WHERE run_id = 'run-1' AND operation = 'apply' AND canonical_type = 'Person'")).longValue())
                .as("a re-applied run appends; both happened")
                .isEqualTo(2);
        assertThat(((Number) single("SELECT records FROM niem_meta.projection_run "
                + "WHERE operation = 'rebuild' AND canonical_type = 'Incident'")).longValue())
                .isEqualTo(1);
        assertThat(single("SELECT DISTINCT mapping FROM niem_meta.projection_run"))
                .isEqualTo("leon-cad-to-canonical@1.0.0");
    }

    // --- rebuild ---------------------------------------------------------

    @Test
    void rebuildReplacesRatherThanMerges() {
        PostgresOdsProjectionWriter writer = open();
        writer.apply(changes("run-1",
                List.of(person("p-1", "Rivera", null), person("p-stale", "Gone", null)),
                List.of(incident("i-1", "LCSO-26-0001")),
                List.of(association("a-1", "p-1", "i-1", "SUSPECT"))));

        writer.rebuild(new CanonicalSnapshot(oneIncident("replay").changes()));

        assertThat(writer.count(personType()))
                .as("a record silver no longer holds must not survive a rebuild")
                .isEqualTo(1);
    }

    @Test
    void rebuildIsRefusedWhenOperationalRecordsReferenceTheProjection() throws SQLException {
        PostgresOdsProjectionWriter writer = open();
        writer.apply(oneIncident("run-1"));
        execute("CREATE SCHEMA ods_workflow");
        execute("CREATE TABLE ods_workflow.review (id serial PRIMARY KEY, "
                + "canonical_id text NOT NULL REFERENCES canonical.person (canonical_id), note text)");
        execute("INSERT INTO ods_workflow.review (canonical_id, note) VALUES ('p-1', 'flagged for steward')");

        assertThatThrownBy(() -> writer.rebuild(new CanonicalSnapshot(oneIncident("replay").changes())))
                .isInstanceOf(ProjectionException.class)
                .hasMessageContaining("operational records a rebuild cannot restore")
                .extracting(e -> ((ProjectionException) e).operation())
                .isEqualTo(Operation.REBUILD);

        assertThat(single("SELECT note FROM ods_workflow.review")).isEqualTo("flagged for steward");
        assertThat(writer.count(personType())).as("the projection is left as it was").isEqualTo(1);
        assertThat(writer.count(associationType())).isEqualTo(1);
    }

    // --- tenancy ---------------------------------------------------------

    @Test
    void aSecondTenantIsRefused() {
        open().apply(oneIncident("run-1"));

        assertThatThrownBy(() -> open(TenantId.of("us.fl.riverton-pd"), model()))
                .isInstanceOf(ProjectionException.class)
                .hasMessageContaining("belongs to 'us.fl.leon-so'")
                .extracting(e -> ((ProjectionException) e).operation())
                .isEqualTo(Operation.INTEGRITY);
    }

    @Test
    void theSameTenantReopens() {
        open().apply(oneIncident("run-1"));

        assertThat(open().count(personType())).isEqualTo(1);
    }

    @Test
    void unclaimedDataIsNotClaimed() throws SQLException {
        execute("CREATE SCHEMA canonical");
        execute("CREATE TABLE canonical.person (canonical_id text PRIMARY KEY)");
        execute("INSERT INTO canonical.person VALUES ('someone')");

        assertThatThrownBy(this::open)
                .isInstanceOf(ProjectionException.class)
                .hasMessageContaining("does not say whose it is")
                .extracting(e -> ((ProjectionException) e).operation())
                .isEqualTo(Operation.INTEGRITY);
    }

    // --- evolution -------------------------------------------------------

    @Test
    void aNewFieldInTheModelBecomesANewColumn() throws SQLException {
        open().apply(oneIncident("run-1"));

        List<CanonicalTypeDescriptor> evolved = List.of(
                personType(new CanonicalFieldDescriptor("aliasNames", FieldType.STRING, false, true,
                        new NiemProvenance(NIEM_CORE, null, "nc:PersonAlternateName"), null, List.of(), null)),
                incidentType(), associationType());
        PostgresOdsProjectionWriter writer = open(LEON, evolved);
        writer.apply(new CanonicalChangeSet("run-2", List.of(new TypedRecords(evolved.get(0), List.of(
                Record.builder(evolved.get(0).qualifiedName())
                        .set("canonicalId", CanonicalId.of("p-1"))
                        .set("aliasNames", List.of("Al", "Lex"))
                        .build())))));

        assertThat(single("SELECT array_to_string(alias_names, ',') FROM canonical.person"))
                .isEqualTo("Al,Lex");
        assertThat(single("SELECT sur_name FROM canonical.person")).isEqualTo("Rivera");
    }

    @Test
    void aColumnWhoseTypeContradictsTheModelIsRefused() {
        open().apply(oneIncident("run-1"));

        List<CanonicalTypeDescriptor> contradicting = List.of(
                new CanonicalTypeDescriptor("Person", "https://niemplatform.gov/canonical/core/1.0",
                        "2.0.0", CanonicalKind.ENTITY,
                        new NiemProvenance(NIEM_CORE, "nc:PersonType", null), null,
                        List.of(niemField("birthDate", FieldType.STRING)), List.of()),
                incidentType(), associationType());

        assertThatThrownBy(() -> open(LEON, contradicting))
                .isInstanceOf(ProjectionException.class)
                .hasMessageContaining("canonical.person.birth_date is date in the database but the model declares text");
    }

    // --- discovery -------------------------------------------------------

    @Test
    void theRegistryOpensTheOdsFromADefinition() {
        ProjectionDefinition definition = new ProjectionDefinition("leon-ods", "1.0.0", ProjectionType.ODS,
                Map.of("jdbcUrl", POSTGRES.getJdbcUrl(), "user", POSTGRES.getUsername(),
                        "passwordEnv", "NIEM_ODS_PASSWORD"));

        ProjectionWriter writer = ProjectionRegistry.discover().open(definition, context(LEON, model()));
        opened.add(writer);

        assertThat(writer.type()).isEqualTo(ProjectionType.ODS);
        writer.apply(oneIncident("run-1"));
        assertThat(writer.count(incidentType())).isEqualTo(1);
    }

    @Test
    void aMisspelledSettingIsRefusedRatherThanIgnored() {
        ProjectionDefinition definition = new ProjectionDefinition("leon-ods", "1.0.0", ProjectionType.ODS,
                Map.of("jdbcUrl", POSTGRES.getJdbcUrl(), "passwrodEnv", "NIEM_ODS_PASSWORD"));

        assertThatThrownBy(() -> new PostgresOdsProjectionFactory().open(definition, context(LEON, model())))
                .isInstanceOf(ProjectionDefinitionException.class)
                .hasMessageContaining("unrecognised setting 'passwrodEnv'");
    }
}
