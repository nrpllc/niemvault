package gov.niemplatform.storage.iceberg;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.storage.api.CanonicalStorageException;
import gov.niemplatform.storage.api.CanonicalStorageException.Operation;
import gov.niemplatform.storage.api.CanonicalStore;
import gov.niemplatform.storage.api.SilverCommit;
import gov.niemplatform.storage.api.SilverSnapshot;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericAppenderFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.encryption.EncryptedFiles;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.jdbc.JdbcCatalog;

/**
 * Canonical silver on Apache Iceberg (spec §2, ADR 0005).
 *
 * <p>Metadata in a JDBC catalog, data in an object store through {@code S3FileIO}. No Hadoop
 * filesystem is constructed anywhere, which is what lets the same code run on a developer machine,
 * in a container, and in an air-gapped data centre against an on-premises object store.
 *
 * <p>One table per canonical type, in the {@code canonical} namespace, with the schema derived
 * from the type descriptor so a table cannot drift from the model that writes to it.
 *
 * <p>Unpartitioned in Phase 1. Partitioning is a performance decision that needs real volumes and
 * real query shapes to make well, and guessing at it now would bake in a layout that is expensive
 * to change once tables hold history.
 */
public final class IcebergCanonicalStore implements CanonicalStore {

    private static final Namespace CANONICAL = Namespace.of("canonical");

    private final JdbcCatalog catalog;

    public IcebergCanonicalStore(IcebergCanonicalStoreConfig config) {
        Objects.requireNonNull(config, "config");
        try {
            JdbcCatalog created = new JdbcCatalog();
            created.initialize(config.catalogName(), config.catalogProperties());
            this.catalog = created;
            createNamespaceIfAbsent();
        } catch (RuntimeException e) {
            throw new CanonicalStorageException(Operation.CREATE, null,
                    "could not open the canonical catalog at " + config.warehouse(), e);
        }
    }

    private void createNamespaceIfAbsent() {
        try {
            catalog.createNamespace(CANONICAL);
        } catch (AlreadyExistsException expected) {
            // Reopening an existing store is the normal case, not an error.
        }
    }

    private static TableIdentifier identifierFor(CanonicalTypeDescriptor descriptor) {
        // Lower-cased: table identifiers are case-insensitive in several catalogs, and a type
        // named Person resolving differently from person would be a miserable bug to find.
        return TableIdentifier.of(CANONICAL, descriptor.name().toLowerCase(Locale.ROOT));
    }

    @Override
    public void ensureTable(CanonicalTypeDescriptor descriptor) {
        TableIdentifier id = identifierFor(descriptor);
        if (catalog.tableExists(id)) {
            return;
        }
        try {
            catalog.createTable(id, IcebergSchemas.toIcebergSchema(descriptor), PartitionSpec.unpartitioned());
        } catch (AlreadyExistsException raced) {
            // Another writer created it between the check and the call. Harmless.
        } catch (RuntimeException e) {
            throw new CanonicalStorageException(Operation.CREATE, descriptor.name(),
                    "could not create the canonical table", e);
        }
    }

    @Override
    public SilverCommit append(CanonicalTypeDescriptor descriptor, List<Record> records) {
        ensureTable(descriptor);
        Table table = catalog.loadTable(identifierFor(descriptor));

        if (records.isEmpty()) {
            // An empty append is a no-op rather than an empty commit: an empty snapshot would add
            // a point to the history that nothing happened at, which makes history harder to read.
            table.refresh();
            return new SilverCommit(descriptor.name(), currentSnapshotId(table), 0);
        }

        try {
            DataFile file = writeDataFile(descriptor, table, records);
            table.newAppend().appendFile(file).commit();
            table.refresh();
            return new SilverCommit(descriptor.name(), currentSnapshotId(table), records.size());
        } catch (IOException | RuntimeException e) {
            throw new CanonicalStorageException(Operation.APPEND, descriptor.name(),
                    "could not commit %d record(s)".formatted(records.size()), e);
        }
    }

    private DataFile writeDataFile(CanonicalTypeDescriptor descriptor, Table table, List<Record> records)
            throws IOException {
        Schema schema = table.schema();
        // Named from the snapshot sequence rather than a clock or a random value: a run that is
        // replayed writes recognisably parallel files, which makes an operator's life easier when
        // comparing two runs by hand.
        String fileName = "%s-%d.parquet".formatted(
                descriptor.name().toLowerCase(Locale.ROOT), table.snapshots().spliterator().estimateSize());
        OutputFile file = table.io().newOutputFile(table.locationProvider().newDataLocation(fileName));

        GenericAppenderFactory appenders = new GenericAppenderFactory(schema);
        try (DataWriter<org.apache.iceberg.data.Record> writer = appenders.newDataWriter(
                EncryptedFiles.plainAsEncryptedOutput(file), FileFormat.PARQUET, null)) {
            for (Record record : records) {
                GenericRecord row = IcebergSchemas.toIcebergRecord(descriptor, schema, record);
                writer.write(row);
            }
        }
        return DataFiles.builder(table.spec())
                .withPath(file.location())
                .withFileSizeInBytes(table.io().newInputFile(file.location()).getLength())
                .withFormat(FileFormat.PARQUET)
                .withRecordCount(records.size())
                .build();
    }

    @Override
    public java.util.stream.Stream<Record> read(CanonicalTypeDescriptor descriptor) {
        return readInternal(descriptor, null);
    }

    @Override
    public java.util.stream.Stream<Record> readAsOf(CanonicalTypeDescriptor descriptor, long snapshotId) {
        return readInternal(descriptor, snapshotId);
    }

    private java.util.stream.Stream<Record> readInternal(CanonicalTypeDescriptor descriptor, Long snapshotId) {
        try {
            Table table = catalog.loadTable(identifierFor(descriptor));
            table.refresh();
            if (table.currentSnapshot() == null) {
                return java.util.stream.Stream.of();
            }
            IcebergGenerics.ScanBuilder scan = IcebergGenerics.read(table);
            if (snapshotId != null) {
                scan = scan.useSnapshot(snapshotId);
            }
            // Materialised rather than lazily streamed: the reader holds object store connections,
            // and a caller that abandons a stream mid-way would leak them. Silver tables in Phase 1
            // are single-agency and small enough that this is the right trade.
            List<Record> records = new ArrayList<>();
            try (CloseableIterable<org.apache.iceberg.data.Record> rows = scan.build()) {
                rows.forEach(row -> records.add(IcebergSchemas.fromIcebergRecord(descriptor, row)));
            }
            return records.stream();
        } catch (NoSuchTableException absent) {
            return java.util.stream.Stream.of();
        } catch (IOException | RuntimeException e) {
            throw new CanonicalStorageException(Operation.READ, descriptor.name(),
                    snapshotId == null ? "could not read" : "could not read at snapshot " + snapshotId, e);
        }
    }

    @Override
    public List<SilverSnapshot> history(CanonicalTypeDescriptor descriptor) {
        try {
            Table table = catalog.loadTable(identifierFor(descriptor));
            table.refresh();
            List<SilverSnapshot> history = new ArrayList<>();
            for (Snapshot snapshot : table.snapshots()) {
                history.add(new SilverSnapshot(
                        snapshot.snapshotId(),
                        Instant.ofEpochMilli(snapshot.timestampMillis()),
                        totalRecordsAt(snapshot)));
            }
            return List.copyOf(history);
        } catch (NoSuchTableException absent) {
            return List.of();
        } catch (RuntimeException e) {
            throw new CanonicalStorageException(Operation.HISTORY, descriptor.name(),
                    "could not read history", e);
        }
    }

    private static long totalRecordsAt(Snapshot snapshot) {
        String total = snapshot.summary() == null ? null : snapshot.summary().get("total-records");
        return total == null ? 0L : Long.parseLong(total);
    }

    @Override
    public long count(CanonicalTypeDescriptor descriptor) {
        try (java.util.stream.Stream<Record> records = read(descriptor)) {
            return records.count();
        }
    }

    @Override
    public void drop(CanonicalTypeDescriptor descriptor) {
        try {
            // purge = true: silver is derived and rebuildable from bronze, so leaving orphaned
            // data files behind would be litter, not caution. Acceptance criterion 6 deletes
            // silver outright and rebuilds it.
            catalog.dropTable(identifierFor(descriptor), true);
        } catch (NoSuchTableException alreadyGone) {
            // Dropping what is not there is the desired end state.
        } catch (RuntimeException e) {
            throw new CanonicalStorageException(Operation.DROP, descriptor.name(),
                    "could not drop the canonical table", e);
        }
    }

    @Override
    public List<String> types() {
        try {
            return catalog.listTables(CANONICAL).stream()
                    .map(TableIdentifier::name)
                    .sorted()
                    .toList();
        } catch (RuntimeException e) {
            throw new CanonicalStorageException(Operation.LIST, null, "could not list canonical tables", e);
        }
    }

    private static long currentSnapshotId(Table table) {
        Snapshot current = table.currentSnapshot();
        return current == null ? -1L : current.snapshotId();
    }

    @Override
    public void close() {
        catalog.close();
    }
}
