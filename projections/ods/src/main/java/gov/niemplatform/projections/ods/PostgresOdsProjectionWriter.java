package gov.niemplatform.projections.ods;

import static gov.niemplatform.projections.ods.OdsNaming.quote;

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
import gov.niemplatform.projections.api.CanonicalChangeSet;
import gov.niemplatform.projections.api.CanonicalSnapshot;
import gov.niemplatform.projections.api.ProjectionContext;
import gov.niemplatform.projections.api.ProjectionException;
import gov.niemplatform.projections.api.ProjectionException.Operation;
import gov.niemplatform.projections.api.ProjectionType;
import gov.niemplatform.projections.api.ProjectionWriter;
import gov.niemplatform.projections.api.TypedRecords;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Projects canonical silver into PostgreSQL, the operational data store (ADR 0035).
 *
 * <h2>How the canonical model becomes tables</h2>
 *
 * <ul>
 *   <li>Every canonical type is a table, entity or association alike, keyed by its canonical
 *       identity. An association is a table rather than a join column because it carries fields of
 *       its own -- how a person was involved in an incident is a fact about neither of them.
 *   <li>Each association role is a {@code <role>_id} column with a foreign key to the endpoint's
 *       table, <strong>deferred to commit</strong>. The graph writer refuses an association whose
 *       endpoint is missing; this is the same refusal, made by the database, at the one moment a
 *       whole change set is visible to it -- so the order of records inside a change set does not
 *       matter, and a missing endpoint still fails the run.
 *   <li>Each field is a typed column, commented with the NIEM element it came from or the written
 *       justification for extending NIEM, so an analyst in {@code psql} can see what a column means
 *       without the model on disk.
 * </ul>
 *
 * <p>Every table is created from the whole model when the writer opens, not from whatever the first
 * change set happens to carry: a foreign key to {@code person} needs the {@code person} table
 * whether or not this run landed anyone.
 *
 * <h2>Projection, and operational store</h2>
 *
 * <p>The schema this writer owns is gold: rebuildable from silver at any time, and rebuilt by
 * replacing it. The rest of the database is the ODS proper -- where operational work on these
 * records is kept, which silver does not hold and a rebuild must never touch. The boundary between
 * the two is enforced by {@link #rebuild}, not by convention; see there.
 *
 * <p>Every write is an upsert on canonical identity, so applying the same change set twice leaves
 * the same tables. Replay depends on that (criterion 6).
 */
public final class PostgresOdsProjectionWriter implements ProjectionWriter {

    /** Where the tenant claim and the run ledger live. Never a canonical schema. */
    static final String META_SCHEMA = "niem_meta";

    private static final String IDENTITY_COLUMN = "canonical_id";

    /** Lineage on every row: which run, from which source, under which mapping, last wrote it. */
    private static final List<String> LINEAGE_COLUMNS =
            List.of("niem_run_id", "niem_source_id", "niem_mapping", "niem_projected_at");

    private static final String SQLSTATE_FOREIGN_KEY_VIOLATION = "23503";
    private static final String SQLSTATE_FEATURE_NOT_SUPPORTED = "0A000";

    private final Connection connection;
    private final String schema;
    private final ProjectionContext context;
    private final Map<String, CanonicalTypeDescriptor> model = new LinkedHashMap<>();

    /**
     * Connects, claims the database for the context's tenant, and puts the schema in place.
     *
     * @param user may be null, for a URL that carries it or a server that needs none
     * @param password may be null
     * @throws ProjectionException if the database is unreachable, belongs to another tenant, or
     *     holds a table whose shape contradicts the model
     */
    public PostgresOdsProjectionWriter(
            String jdbcUrl, String user, String password, String schema, ProjectionContext context) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.context = Objects.requireNonNull(context, "context");
        context.model().forEach(descriptor -> {
            model.put(descriptor.qualifiedName(), descriptor);
            model.put(descriptor.name(), descriptor);
        });

        Properties properties = new Properties();
        if (user != null) {
            properties.setProperty("user", user);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        try {
            this.connection = DriverManager.getConnection(jdbcUrl, properties);
            this.connection.setAutoCommit(false);
        } catch (SQLException e) {
            // The URL only: it can carry a password as a parameter, but it is what an operator needs
            // to see, and a password written into a URL is already in their shell history.
            throw new ProjectionException(Operation.CONNECT, ProjectionType.ODS, null,
                    "could not connect to " + jdbcUrl.replaceAll("password=[^&]*", "password=***")
                            + ": " + describe(e), e);
        }

        try {
            claimForTenant();
            createLedger();
            createSchema();
            connection.commit();
        } catch (ProjectionException e) {
            rollbackQuietly();
            closeQuietly();
            throw e;
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly();
            closeQuietly();
            throw new ProjectionException(Operation.CONNECT, ProjectionType.ODS, null,
                    "could not prepare schema '" + schema + "': " + describe(e), e);
        }
    }

    @Override
    public ProjectionType type() {
        return ProjectionType.ODS;
    }

    // --- open: tenant, ledger, schema ---------------------------------------

    /**
     * Claims an unclaimed database for this tenant, or verifies an existing claim.
     *
     * <p>ADR 0026: a deployment serves one agency, and the store is what makes that true. Two runs
     * against one database under different tenants would put two agencies' records in one table,
     * and nothing reading it afterwards could tell them apart.
     */
    private void claimForTenant() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + quote(META_SCHEMA));
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS %s.deployment (
                        singleton  boolean PRIMARY KEY DEFAULT true CHECK (singleton),
                        tenant     text NOT NULL,
                        claimed_at timestamptz NOT NULL DEFAULT now()
                    )""".formatted(quote(META_SCHEMA)));
            statement.execute("COMMENT ON TABLE %s.deployment IS %s".formatted(quote(META_SCHEMA),
                    literal("The one agency whose records this database holds (ADR 0026). "
                            + "Written the first time anything is stored, checked on every open.")));
        }

        String claimed = null;
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT tenant FROM %s.deployment".formatted(quote(META_SCHEMA)))) {
            if (rows.next()) {
                claimed = rows.getString(1);
            }
        }
        String tenant = context.tenant().value();
        if (claimed != null) {
            if (!claimed.equals(tenant)) {
                throw new ProjectionException(Operation.INTEGRITY, ProjectionType.ODS, null,
                        "this database belongs to '" + claimed + "' and cannot also hold data for '"
                                + tenant + "'. A deployment serves one agency (ADR 0026); give this "
                                + "tenant its own database", null);
            }
            return;
        }

        // Only claim a schema that holds nothing. Tables with rows but no claim predate this check,
        // and stamping an agency's name on records of unknown origin is worse than refusing them.
        String occupied = firstOccupiedTable();
        if (occupied != null) {
            throw new ProjectionException(Operation.INTEGRITY, ProjectionType.ODS, null,
                    "schema '" + schema + "' already holds data (in " + occupied + ") but the "
                            + "database does not say whose it is. Claiming it for '" + tenant
                            + "' would assert an origin nobody recorded; insert the claim into "
                            + META_SCHEMA + ".deployment deliberately if you know the answer", null);
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO %s.deployment (tenant) VALUES (?)".formatted(quote(META_SCHEMA)))) {
            insert.setString(1, tenant);
            insert.executeUpdate();
        }
    }

    private String firstOccupiedTable() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = ? AND table_type = 'BASE TABLE' ORDER BY table_name")) {
            query.setString(1, schema);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    tables.add(rows.getString(1));
                }
            }
        }
        for (String table : tables) {
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery(
                            "SELECT EXISTS (SELECT 1 FROM %s)".formatted(qualified(table)))) {
                if (rows.next() && rows.getBoolean(1)) {
                    return schema + "." + table;
                }
            }
        }
        return null;
    }

    /**
     * The run ledger: which run applied or rebuilt how many records of which type, and when.
     *
     * <p>Append-only, and deliberately so. It is lineage -- an event log of what reached the ODS --
     * so a replay that re-applies a run appends another row rather than overwriting the first. Both
     * happened, and the second one is exactly what somebody investigating a changed value needs to
     * see.
     */
    private void createLedger() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS %s.projection_run (
                        id              bigserial PRIMARY KEY,
                        run_id          text NOT NULL,
                        operation       text NOT NULL CHECK (operation IN ('apply', 'rebuild')),
                        target_schema   text NOT NULL,
                        canonical_type  text NOT NULL,
                        records         bigint NOT NULL,
                        source_id       text NOT NULL,
                        mapping         text NOT NULL,
                        applied_at      timestamptz NOT NULL DEFAULT now()
                    )""".formatted(quote(META_SCHEMA)));
            statement.execute("COMMENT ON TABLE %s.projection_run IS %s".formatted(quote(META_SCHEMA),
                    literal("Append-only ledger of every apply and rebuild that reached the ODS. "
                            + "Lineage, not state: a replay appends rather than overwrites.")));
        }
    }

    /**
     * Declares a table for every type in the model, adding what is missing and refusing what
     * contradicts it.
     *
     * <p>Additive evolution only. A new field in a new content version is a new nullable column; a
     * field whose type changed is refused, naming the column, because {@code ALTER COLUMN TYPE} on a
     * populated table either fails halfway or succeeds by casting, and a cast that silently turns a
     * date into text is the kind of change §4.7 exists to surface rather than perform.
     */
    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + quote(schema));
        }

        List<String> conflicts = new ArrayList<>();
        for (CanonicalTypeDescriptor descriptor : descriptors()) {
            String table = OdsNaming.table(descriptor.name());
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS %s (%s text PRIMARY KEY)"
                        .formatted(qualified(table), quote(IDENTITY_COLUMN)));
            }

            Map<String, String> existing = existingColumns(table);
            for (Map.Entry<String, String> column : declaredColumns(descriptor).entrySet()) {
                String present = existing.get(column.getKey());
                if (present == null) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS %s %s".formatted(
                                qualified(table), quote(column.getKey()), ddlType(column.getValue())));
                    }
                } else if (!present.equals(column.getValue())) {
                    conflicts.add("%s.%s.%s is %s in the database but the model declares %s".formatted(
                            schema, table, column.getKey(), present, column.getValue()));
                }
            }
            comment(descriptor, table);
        }
        if (!conflicts.isEmpty()) {
            throw new ProjectionException(Operation.CONNECT, ProjectionType.ODS, null,
                    "the ODS contradicts the canonical model and is not altered to match: "
                            + String.join("; ", conflicts), null);
        }

        // Foreign keys after every table exists, since a role can point at a type declared later.
        for (CanonicalTypeDescriptor descriptor : descriptors()) {
            for (CanonicalRoleDescriptor role : descriptor.roles()) {
                addRoleConstraint(descriptor, role);
            }
        }
    }

    private void addRoleConstraint(CanonicalTypeDescriptor descriptor, CanonicalRoleDescriptor role)
            throws SQLException {
        String table = OdsNaming.table(descriptor.name());
        String column = OdsNaming.roleColumn(role.name());

        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE INDEX IF NOT EXISTS %s ON %s (%s)".formatted(
                    quote(OdsNaming.objectName("ix", table, column)), qualified(table), quote(column)));
        }

        // A role whose target is not in this model gets no foreign key: there is no table to point
        // at, and inventing one would declare a type the model does not. The column is still
        // written, so nothing is lost; only the database's check on it is.
        if (!model.containsKey(role.targetType())) {
            return;
        }

        String constraint = OdsNaming.objectName("fk", table, column);
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM pg_constraint WHERE conname = ? AND conrelid = ?::regclass")) {
            query.setString(1, constraint);
            query.setString(2, qualified(table));
            try (ResultSet rows = query.executeQuery()) {
                if (rows.next()) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    ALTER TABLE %s ADD CONSTRAINT %s FOREIGN KEY (%s)
                        REFERENCES %s (%s) DEFERRABLE INITIALLY DEFERRED""".formatted(
                            qualified(table), quote(constraint), quote(column),
                            qualified(OdsNaming.table(role.targetType())), quote(IDENTITY_COLUMN)));
        }
    }

    private void comment(CanonicalTypeDescriptor descriptor, String table) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("COMMENT ON TABLE %s IS %s".formatted(qualified(table), literal(
                    "Canonical %s %s@%s. %s".formatted(
                            descriptor.kind() == CanonicalKind.ASSOCIATION ? "association" : "entity",
                            descriptor.qualifiedName(), descriptor.version(),
                            origin(descriptor.provenance(), descriptor.extension())))));
            statement.execute("COMMENT ON COLUMN %s.%s IS %s".formatted(qualified(table),
                    quote(IDENTITY_COLUMN), literal("Platform-assigned canonical identity.")));
            for (CanonicalRoleDescriptor role : descriptor.roles()) {
                statement.execute("COMMENT ON COLUMN %s.%s IS %s".formatted(qualified(table),
                        quote(OdsNaming.roleColumn(role.name())), literal(
                                "Role '%s': canonical identity of the %s. %s".formatted(role.name(),
                                        role.targetType(), origin(role.provenance(), role.extension())))));
            }
            for (CanonicalFieldDescriptor field : descriptor.fields()) {
                statement.execute("COMMENT ON COLUMN %s.%s IS %s".formatted(qualified(table),
                        quote(OdsNaming.column(field.name())), literal(
                                "Canonical field '%s'%s. %s".formatted(field.name(),
                                        field.codeList().isEmpty() ? ""
                                                : ", one of " + field.codeList(),
                                        origin(field.provenance(), field.extension())))));
            }
        }
    }

    private static String origin(NiemProvenance provenance, ExtensionJustification extension) {
        if (provenance != null) {
            return "NIEM " + provenance.reference() + " (" + provenance.niemNamespace() + ")";
        }
        return "Extension to NIEM: " + extension.text().strip();
    }

    /** Column name to PostgreSQL {@code udt_name}, the form information_schema reports. */
    private static Map<String, String> declaredColumns(CanonicalTypeDescriptor descriptor) {
        Map<String, String> columns = new LinkedHashMap<>();
        for (CanonicalRoleDescriptor role : descriptor.roles()) {
            columns.put(OdsNaming.roleColumn(role.name()), "text");
        }
        for (CanonicalFieldDescriptor field : descriptor.fields()) {
            String udt = udt(field.type());
            columns.put(OdsNaming.column(field.name()), field.repeated() ? "_" + udt : udt);
        }
        columns.put("niem_run_id", "text");
        columns.put("niem_source_id", "text");
        columns.put("niem_mapping", "text");
        columns.put("niem_projected_at", "timestamptz");
        return columns;
    }

    private Map<String, String> existingColumns(String table) throws SQLException {
        Map<String, String> columns = new LinkedHashMap<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT column_name, udt_name FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ?")) {
            query.setString(1, schema);
            query.setString(2, table);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    columns.put(rows.getString(1), rows.getString(2));
                }
            }
        }
        return columns;
    }

    /**
     * The canonical field type as PostgreSQL names it internally.
     *
     * <p>Dates and instants stay temporal and decimals stay exact -- the ODS is the one projection
     * that can hold every canonical value without loss, which is part of why it is the store
     * operational work is recorded against.
     */
    private static String udt(FieldType type) {
        return switch (type) {
            case STRING, CODE, REF, IDENTITY -> "text";
            case DATE -> "date";
            case DATE_TIME -> "timestamptz";
            case INTEGER -> "int8";
            case DECIMAL -> "numeric";
            case BOOLEAN -> "bool";
        };
    }

    private static String ddlType(String udt) {
        return udt.startsWith("_") ? udt.substring(1) + "[]" : udt;
    }

    // --- apply -----------------------------------------------------------

    /**
     * Upserts a change set in one transaction.
     *
     * <p>An absent value does not erase a present one: {@code COALESCE(EXCLUDED.x, t.x)}. That is
     * the graph writer's {@code SET n += $properties} restated in SQL, and it has to be the same
     * rule. Two projections that disagreed about whether a later record without a birth date clears
     * the earlier one would answer the same question differently, and that divergence is what §4.7
     * exists to detect -- not something to build in.
     */
    @Override
    public void apply(CanonicalChangeSet changes) {
        if (changes.isEmpty()) {
            return;
        }
        write(Operation.APPLY, changes.runId(), changes.changes(), false);
    }

    // --- rebuild ---------------------------------------------------------

    /**
     * Replaces the snapshot's tables with its contents, in one transaction.
     *
     * <p><strong>The ODS invariant (ADR 0035).</strong> The tables are emptied with a single
     * {@code TRUNCATE} and <em>without</em> {@code CASCADE}. If anything outside the projection
     * holds a real foreign key into these tables -- a review, a case assignment, any operational
     * record kept in the ODS against a person -- PostgreSQL refuses the truncate, and so this
     * refuses the rebuild. {@code CASCADE} would have emptied that operational table too: silently,
     * and permanently, because silver does not hold it and no replay can bring it back. A rebuild
     * that cannot run until someone decides what happens to operational state is the correct
     * outcome; one that destroyed it would be the worst thing this writer could do.
     *
     * <p>Only the snapshot's own types are replaced, as the graph writer does. A replay reproduces
     * one mapping's output, and emptying types it does not carry would delete records it has no
     * means to restore.
     *
     * <p>A failed rebuild rolls back and leaves the previous projection as it was, which a graph
     * rebuild -- delete, then write -- cannot promise.
     */
    @Override
    public void rebuild(CanonicalSnapshot snapshot) {
        write(Operation.REBUILD, "rebuild", snapshot.contents(), true);
    }

    private void write(Operation operation, String runId, List<TypedRecords> groups, boolean replace) {
        String current = null;
        try {
            if (replace && !groups.isEmpty()) {
                List<String> tables = groups.stream()
                        .map(typed -> qualified(OdsNaming.table(declared(typed.descriptor()).name())))
                        .distinct()
                        .toList();
                try (Statement statement = connection.createStatement()) {
                    statement.execute("TRUNCATE " + String.join(", ", tables));
                }
            }
            for (TypedRecords typed : groups) {
                CanonicalTypeDescriptor descriptor = declared(typed.descriptor());
                current = descriptor.name();
                upsert(descriptor, typed.records(), runId);
                ledger(runId, replace ? "rebuild" : "apply", descriptor, typed.size());
            }
            current = null;
            // Deferred foreign keys are checked here, with the whole change set visible.
            connection.commit();
        } catch (ProjectionException e) {
            rollbackQuietly();
            throw e;
        } catch (SQLException e) {
            rollbackQuietly();
            throw new ProjectionException(operation, ProjectionType.ODS, current,
                    explain(e, groups), e);
        } catch (RuntimeException e) {
            rollbackQuietly();
            throw new ProjectionException(operation, ProjectionType.ODS, current,
                    "could not write %d record(s): %s".formatted(total(groups), e.getMessage()), e);
        }
    }

    private String explain(SQLException e, List<TypedRecords> groups) {
        String state = sqlState(e);
        if (SQLSTATE_FOREIGN_KEY_VIOLATION.equals(state)) {
            return "an association references an entity the ODS does not hold; entities must be "
                    + "projected before, or with, the associations that reference them. Nothing "
                    + "from this change set was written. " + describe(e);
        }
        if (SQLSTATE_FEATURE_NOT_SUPPORTED.equals(state)) {
            return "a table outside the projection holds a foreign key into it, so emptying it "
                    + "would destroy operational records a rebuild cannot restore (ADR 0035). "
                    + "Nothing was changed. " + describe(e);
        }
        return "could not write %d record(s): %s".formatted(total(groups), describe(e));
    }

    private void upsert(CanonicalTypeDescriptor descriptor, List<Record> records, String runId)
            throws SQLException {
        if (records.isEmpty()) {
            return;
        }
        String table = OdsNaming.table(descriptor.name());
        List<String> columns = new ArrayList<>();
        columns.add(IDENTITY_COLUMN);
        descriptor.roles().forEach(role -> columns.add(OdsNaming.roleColumn(role.name())));
        descriptor.fields().forEach(field -> columns.add(OdsNaming.column(field.name())));
        columns.addAll(LINEAGE_COLUMNS);

        List<String> placeholders = new ArrayList<>();
        columns.forEach(column -> placeholders.add(
                column.equals("niem_projected_at") ? "now()" : "?"));

        List<String> updates = new ArrayList<>();
        for (String column : columns) {
            if (column.equals(IDENTITY_COLUMN)) {
                continue;
            }
            updates.add(LINEAGE_COLUMNS.contains(column)
                    ? "%1$s = EXCLUDED.%1$s".formatted(quote(column))
                    : "%1$s = COALESCE(EXCLUDED.%1$s, t.%1$s)".formatted(quote(column)));
        }

        String sql = "INSERT INTO %s AS t (%s) VALUES (%s) ON CONFLICT (%s) DO UPDATE SET %s".formatted(
                qualified(table),
                String.join(", ", columns.stream().map(OdsNaming::quote).toList()),
                String.join(", ", placeholders),
                quote(IDENTITY_COLUMN),
                String.join(", ", updates));

        boolean binary = descriptor.kind() == CanonicalKind.ASSOCIATION && descriptor.roles().size() == 2;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Record record : records) {
                int index = 1;
                statement.setString(index++, identityOf(record, descriptor));
                for (CanonicalRoleDescriptor role : descriptor.roles()) {
                    CanonicalRef endpoint = record.get(role.name(), CanonicalRef.class);
                    if (endpoint == null && binary) {
                        // The graph writer's refusal, for the same reason: a half-connected
                        // association is worse than none, and the mapping should not have emitted it.
                        throw new ProjectionException(Operation.APPLY, ProjectionType.ODS,
                                descriptor.name(), "an association is missing its '" + role.name()
                                        + "' reference; the mapping should not have emitted it, and "
                                        + "a half-connected association is worse than none", null);
                    }
                    statement.setString(index++, endpoint == null ? null : endpoint.id().value());
                }
                for (CanonicalFieldDescriptor field : descriptor.fields()) {
                    bind(statement, index++, field, record.raw(field.name()));
                }
                statement.setString(index++, runId);
                statement.setString(index++, context.sourceId());
                statement.setString(index, context.qualifiedMapping());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void bind(PreparedStatement statement, int index, CanonicalFieldDescriptor field, Object value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, field.repeated() ? Types.ARRAY : sqlType(field.type()));
            return;
        }
        if (value instanceof List<?> list) {
            Object[] elements = list.stream().map(PostgresOdsProjectionWriter::toSqlValue).toArray();
            Array array = connection.createArrayOf(udt(field.type()), elements);
            statement.setArray(index, array);
            return;
        }
        statement.setObject(index, toSqlValue(value), sqlType(field.type()));
    }

    private static int sqlType(FieldType type) {
        return switch (type) {
            case STRING, CODE, REF, IDENTITY -> Types.VARCHAR;
            case DATE -> Types.DATE;
            case DATE_TIME -> Types.TIMESTAMP_WITH_TIMEZONE;
            case INTEGER -> Types.BIGINT;
            case DECIMAL -> Types.NUMERIC;
            case BOOLEAN -> Types.BOOLEAN;
        };
    }

    private static Object toSqlValue(Object value) {
        if (value instanceof CanonicalId identity) {
            return identity.value();
        }
        if (value instanceof CanonicalRef reference) {
            return reference.typeName() + "/" + reference.id().value();
        }
        if (value instanceof Instant instant) {
            // The driver has no Instant mapping; an offset date-time at UTC is the same moment and
            // lands in a timestamptz column as one.
            return instant.atOffset(ZoneOffset.UTC);
        }
        // LocalDate, BigDecimal, Long, Boolean and String bind as themselves.
        return value;
    }

    private void ledger(String runId, String operation, CanonicalTypeDescriptor descriptor, long records)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO %s.projection_run
                    (run_id, operation, target_schema, canonical_type, records, source_id, mapping)
                VALUES (?, ?, ?, ?, ?, ?, ?)""".formatted(quote(META_SCHEMA)))) {
            insert.setString(1, runId);
            insert.setString(2, operation);
            insert.setString(3, schema);
            insert.setString(4, descriptor.name());
            insert.setLong(5, records);
            insert.setString(6, context.sourceId());
            insert.setString(7, context.qualifiedMapping());
            insert.executeUpdate();
        }
    }

    // --- counts ----------------------------------------------------------

    @Override
    public long count(CanonicalTypeDescriptor descriptor) {
        String table = OdsNaming.table(descriptor.name());
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + qualified(table))) {
            rows.next();
            long total = rows.getLong(1);
            connection.commit();
            return total;
        } catch (SQLException e) {
            rollbackQuietly();
            throw new ProjectionException(Operation.COUNT, ProjectionType.ODS, descriptor.name(),
                    "could not count " + schema + "." + table + ": " + describe(e), e);
        }
    }

    // --- helpers ---------------------------------------------------------

    /**
     * The model's own descriptor for a type, which is the one the tables were declared from.
     *
     * <p>A change set carrying a type the writer was not opened with has nowhere to go: its table was
     * never declared, and declaring it now, mid-transaction, from whatever the change set says would
     * let one run's view of the model reshape the store.
     */
    private CanonicalTypeDescriptor declared(CanonicalTypeDescriptor descriptor) {
        CanonicalTypeDescriptor known = model.get(descriptor.qualifiedName());
        if (known == null) {
            throw new ProjectionException(Operation.APPLY, ProjectionType.ODS, descriptor.name(),
                    "the type is not in the canonical model this projection was opened with, so the "
                            + "ODS has no table declared for it", null);
        }
        return known;
    }

    private List<CanonicalTypeDescriptor> descriptors() {
        return model.values().stream().distinct().toList();
    }

    private String qualified(String table) {
        return quote(schema) + "." + quote(table);
    }

    private static String identityOf(Record record, CanonicalTypeDescriptor descriptor) {
        CanonicalId identity = record.get(CanonicalTypeDescriptor.CANONICAL_ID_FIELD, CanonicalId.class);
        if (identity == null) {
            throw new ProjectionException(Operation.APPLY, ProjectionType.ODS, descriptor.name(),
                    "a canonical record reached the projection without an identity", null);
        }
        return identity.value();
    }

    /** A SQL string literal. COMMENT takes no bind parameters, so the text is escaped instead. */
    private static String literal(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    private static int total(List<TypedRecords> groups) {
        return groups.stream().mapToInt(TypedRecords::size).sum();
    }

    private static String sqlState(SQLException e) {
        for (SQLException current = e; current != null; current = current.getNextException()) {
            if (current.getSQLState() != null) {
                return current.getSQLState();
            }
        }
        return null;
    }

    /**
     * The server's message and SQLSTATE. A batch failure arrives as a generic "batch entry N was
     * aborted" with the real cause chained behind it, so the chain is walked to the last entry.
     */
    private static String describe(Exception e) {
        if (!(e instanceof SQLException sql)) {
            return String.valueOf(e.getMessage());
        }
        SQLException cause = sql;
        while (cause.getNextException() != null) {
            cause = cause.getNextException();
        }
        return "%s [SQLSTATE %s]".formatted(cause.getMessage(), cause.getSQLState());
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original failure is what the operator needs; a failed rollback on a connection
            // that is already broken adds nothing, and the transaction dies with the connection.
        }
    }

    private void closeQuietly() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Closing after a failed open. The failure being reported is the useful one.
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new ProjectionException(Operation.CONNECT, ProjectionType.ODS, null,
                    "could not close the connection: " + describe(e), e);
        }
    }
}
