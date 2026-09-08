package gov.niemplatform.storage.parquet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.niemplatform.storage.api.BronzeBatch;
import gov.niemplatform.storage.api.BronzeBatchReceipt;
import gov.niemplatform.storage.api.BronzeRange;
import gov.niemplatform.storage.api.BronzeStorageException;
import gov.niemplatform.storage.api.BronzeStorageException.Operation;
import gov.niemplatform.storage.api.BronzeStore;
import gov.niemplatform.storage.api.ContentHash;
import gov.niemplatform.storage.api.RawEnvelope;
import gov.niemplatform.storage.api.SourceOffset;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;

/**
 * Bronze on Parquet with a sidecar JSON manifest per batch (spec §2, §4.4).
 *
 * <p>Layout, one directory per batch:
 *
 * <pre>
 *   &lt;root&gt;/&lt;sourceId&gt;/&lt;batchId&gt;/data.parquet   -- envelopes, payloads byte-preserved
 *   &lt;root&gt;/&lt;sourceId&gt;/&lt;batchId&gt;/manifest.json  -- what landed, readable without Parquet
 * </pre>
 *
 * <p><strong>The manifest is the commit.</strong> It is written last, via an atomic rename, and a
 * batch directory without one is invisible to every read path. A crash part-way through therefore
 * leaves an orphaned data file rather than a partially readable batch -- which matters because
 * silver must rebuild identically from bronze every time.
 *
 * <p>Reads and writes go through parquet-java's NIO file abstractions, never a Hadoop
 * {@code FileSystem}. That is what lets bronze run on a developer machine and in an air-gapped
 * container without a Hadoop installation or {@code winutils.exe}. See ADR 0005.
 *
 * <p>Timestamps are stored as ISO-8601 text rather than epoch millis: exact to the nanosecond,
 * and legible in a raw Parquet dump during an audit. Storage cost is the deliberate trade.
 */
public final class ParquetBronzeStore implements BronzeStore {

    private static final String DATA_FILE = "data.parquet";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final String MANIFEST_TEMP = "manifest.json.partial";

    /** Envelope shape in Parquet. Mirrors {@link RawEnvelope}; spec §4.4 fixes the members. */
    static final Schema ENVELOPE_SCHEMA = SchemaBuilder.record("RawEnvelope")
            .namespace("gov.niemplatform.storage")
            .fields()
            .requiredString("envelopeId")
            .requiredString("sourceId")
            .requiredString("connectorInstanceId")
            .requiredString("ingestTimestamp")
            .optionalString("sourceAssertedTimestamp")
            .requiredBytes("payload")
            .requiredString("contentHash")
            .requiredString("offset")
            .endRecord();

    private final Path root;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final ReentrantLock appendLock = new ReentrantLock();

    /**
     * The file naming the agency this store belongs to.
     *
     * <p>Written the first time anything is stored and checked on every open. A deployment serves
     * one tenant (ADR 0026), and this is what makes that a property of the store rather than a
     * statement in a document.
     */
    private static final String TENANT_MARKER = ".tenant";

    private final gov.niemplatform.canonical.meta.TenantId tenant;

    public ParquetBronzeStore(Path root, gov.niemplatform.canonical.meta.TenantId tenant) {
        this(root, tenant, Clock.systemUTC());
    }

    /**
     * Opens a bronze store for one agency.
     *
     * @throws BronzeStorageException if the root already holds another agency's data. Refused on
     *     open rather than filtered on read: two agencies' raw records interleaved in one store is
     *     not a condition to detect later, and bronze is append-only, so there is no undoing it
     */
    public ParquetBronzeStore(
            Path root, gov.niemplatform.canonical.meta.TenantId tenant, Clock clock) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath();
        this.tenant = Objects.requireNonNull(tenant, "tenant");
        this.clock = Objects.requireNonNull(clock, "clock");
        claimForTenant();
    }

    /**
     * Opens an existing store for reading, adopting whatever tenant it already declares.
     *
     * <p>For readers that observe a store rather than write to one — the authoring surface profiling
     * column shapes, for instance. It reads the claim rather than making one, and refuses a store
     * that does not say whose it is, because a reader that guesses is a reader that reports one
     * agency's data as another's.
     */
    public static ParquetBronzeStore openExisting(Path root) {
        Path marker = root.toAbsolutePath().resolve(TENANT_MARKER);
        try {
            if (!Files.exists(marker)) {
                throw new BronzeStorageException(Operation.READ, "(unknown)", root.toString(),
                        "this store does not declare a tenant, so nothing can say whose data it is");
            }
            return new ParquetBronzeStore(root, gov.niemplatform.canonical.meta.TenantId.of(
                    Files.readString(marker, java.nio.charset.StandardCharsets.UTF_8).trim()));
        } catch (IOException e) {
            throw new BronzeStorageException(Operation.READ, "(unknown)", root.toString(),
                    "cannot read the tenant of this store", e);
        }
    }

    /** The agency whose records this store holds. */
    public gov.niemplatform.canonical.meta.TenantId tenant() {
        return tenant;
    }

    /**
     * Claims an empty root for this tenant, or verifies an existing claim.
     *
     * <p>The check is the whole point of ADR 0026. Without it, two runs against one bronze root
     * under different tenants interleave two agencies' raw data and nothing notices -- and raw data
     * is the one thing the platform promises never to rewrite.
     */
    private void claimForTenant() {
        Path marker = root.resolve(TENANT_MARKER);
        try {
            if (Files.exists(marker)) {
                String claimed = Files.readString(marker, java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!claimed.equals(tenant.value())) {
                    throw new BronzeStorageException(Operation.INTEGRITY, tenant.value(),
                            root.toString(),
                            "this store belongs to '" + claimed + "' and cannot also hold data for '"
                                    + tenant.value() + "'. A deployment serves one agency "
                                    + "(ADR 0026); give this tenant its own store");
                }
                return;
            }
            Files.createDirectories(root);
            // Only claim a root that has nothing in it. A root with batches but no marker predates
            // this check, and silently stamping somebody's name on data of unknown origin is worse
            // than refusing to touch it.
            try (var existing = Files.list(root)) {
                if (existing.findAny().isPresent()) {
                    throw new BronzeStorageException(Operation.INTEGRITY, tenant.value(),
                            root.toString(),
                            "this store already holds data but does not say whose it is. Claiming it "
                                    + "for '" + tenant.value() + "' would assert an origin nobody "
                                    + "recorded; write " + TENANT_MARKER + " deliberately if you know "
                                    + "the answer");
                }
            }
            Files.writeString(marker, tenant.value(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BronzeStorageException(Operation.INTEGRITY, tenant.value(), root.toString(),
                    "cannot establish the tenant of this store", e);
        }
    }

    // --- append ----------------------------------------------------------

    @Override
    public BronzeBatchReceipt append(BronzeBatch batch) {
        appendLock.lock();
        try {
            Path sourceDir = root.resolve(batch.sourceId());
            Files.createDirectories(sourceDir);

            String batchId = nextBatchId(sourceDir);
            Path batchDir = sourceDir.resolve(batchId);
            Files.createDirectories(batchDir);

            writeData(batch, batchDir.resolve(DATA_FILE));

            BronzeBatchReceipt receipt = new BronzeBatchReceipt(
                    batchId,
                    batch.sourceId(),
                    batch.connectorInstanceId(),
                    batch.size(),
                    clock.instant(),
                    batch.firstOffset(),
                    batch.lastOffset(),
                    relative(batchDir.resolve(DATA_FILE)),
                    relative(batchDir.resolve(MANIFEST_FILE)));

            commitManifest(batch, receipt, batchDir);
            return receipt;
        } catch (IOException e) {
            throw new BronzeStorageException(Operation.APPEND, batch.sourceId(), root.toString(),
                    "could not land a batch of " + batch.size() + " record(s)", e);
        } finally {
            appendLock.unlock();
        }
    }

    private void writeData(BronzeBatch batch, Path dataFile) throws IOException {
        try (ParquetWriter<GenericRecord> writer = AvroParquetWriter
                .<GenericRecord>builder(new LocalOutputFile(dataFile))
                .withSchema(ENVELOPE_SCHEMA)
                .withCompressionCodec(CompressionCodecName.SNAPPY)
                .build()) {
            for (RawEnvelope envelope : batch.envelopes()) {
                writer.write(toAvro(envelope));
            }
        }
    }

    /**
     * Writes the manifest to a temporary name and renames it into place.
     *
     * <p>The rename is the commit point. Readers treat a batch directory without a manifest as
     * not present, so a partial write is invisible rather than corrupt.
     */
    private void commitManifest(BronzeBatch batch, BronzeBatchReceipt receipt, Path batchDir) throws IOException {
        ObjectNode manifest = json.createObjectNode();
        manifest.put("batchId", receipt.batchId());
        manifest.put("sourceId", receipt.sourceId());
        manifest.put("connectorInstanceId", receipt.connectorInstanceId());
        manifest.put("recordCount", receipt.recordCount());
        manifest.put("landedAt", receipt.landedAt().toString());
        manifest.put("firstOffset", receipt.firstOffset().value());
        manifest.put("lastOffset", receipt.lastOffset().value());
        manifest.put("dataFile", DATA_FILE);

        // Envelope metadata, never payloads: the manifest makes bronze inspectable without a
        // Parquet reader, and must not become a plaintext copy of the source data.
        ArrayNode envelopes = manifest.putArray("envelopes");
        for (RawEnvelope envelope : batch.envelopes()) {
            ObjectNode entry = envelopes.addObject();
            entry.put("envelopeId", envelope.envelopeId());
            entry.put("offset", envelope.offset().value());
            entry.put("contentHash", envelope.contentHash().toString());
            entry.put("payloadBytes", envelope.payloadSize());
            entry.put("ingestTimestamp", envelope.ingestTimestamp().toString());
            envelope.sourceAssertedTimestamp()
                    .ifPresent(asserted -> entry.put("sourceAssertedTimestamp", asserted.toString()));
        }

        Path temp = batchDir.resolve(MANIFEST_TEMP);
        Files.writeString(temp, json.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                StandardCharsets.UTF_8);
        Path target = batchDir.resolve(MANIFEST_FILE);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Next batch identifier for a source: zero-padded so identifiers sort by landing order.
     *
     * <p>Derived from what is already on disk rather than from a counter held in memory, so a
     * restarted process continues the sequence instead of colliding with it.
     */
    private String nextBatchId(Path sourceDir) throws IOException {
        long highest = 0;
        try (Stream<Path> entries = Files.list(sourceDir)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry) && name.startsWith("b-")) {
                    try {
                        highest = Math.max(highest, Long.parseLong(name.substring(2)));
                    } catch (NumberFormatException ignored) {
                        // A directory that is not one of ours does not participate in numbering.
                    }
                }
            }
        }
        return "b-%012d".formatted(highest + 1);
    }

    // --- read ------------------------------------------------------------

    @Override
    public List<BronzeBatchReceipt> batches(String sourceId) {
        Path sourceDir = root.resolve(sourceId);
        if (!Files.isDirectory(sourceDir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(sourceDir)) {
            List<BronzeBatchReceipt> receipts = new ArrayList<>();
            for (Path batchDir : entries.sorted().toList()) {
                // No manifest means the batch never committed. Invisible, by design.
                Path manifest = batchDir.resolve(MANIFEST_FILE);
                if (Files.isRegularFile(manifest)) {
                    receipts.add(readManifest(sourceId, manifest));
                }
            }
            return List.copyOf(receipts);
        } catch (IOException e) {
            throw new BronzeStorageException(Operation.LIST, sourceId, sourceDir.toString(),
                    "could not list landed batches", e);
        }
    }

    private BronzeBatchReceipt readManifest(String sourceId, Path manifestFile) throws IOException {
        ObjectNode manifest = (ObjectNode) json.readTree(Files.readString(manifestFile, StandardCharsets.UTF_8));
        return new BronzeBatchReceipt(
                manifest.get("batchId").asText(),
                manifest.get("sourceId").asText(),
                manifest.get("connectorInstanceId").asText(),
                manifest.get("recordCount").asInt(),
                Instant.parse(manifest.get("landedAt").asText()),
                SourceOffset.of(manifest.get("firstOffset").asText()),
                SourceOffset.of(manifest.get("lastOffset").asText()),
                relative(manifestFile.getParent().resolve(DATA_FILE)),
                relative(manifestFile));
    }

    @Override
    public Stream<RawEnvelope> read(String sourceId, BronzeRange range) {
        List<BronzeBatchReceipt> selected = batches(sourceId).stream()
                .filter(receipt -> range.includes(receipt.batchId()))
                .toList();

        // flatMap keeps one batch in memory at a time rather than materialising the whole range.
        return selected.stream().flatMap(receipt -> readBatch(sourceId, receipt).stream());
    }

    private List<RawEnvelope> readBatch(String sourceId, BronzeBatchReceipt receipt) {
        Path dataFile = root.resolve(receipt.dataFile());
        List<RawEnvelope> envelopes = new ArrayList<>(receipt.recordCount());
        try (ParquetReader<GenericRecord> reader = AvroParquetReader
                .<GenericRecord>builder(new LocalInputFile(dataFile))
                .build()) {
            GenericRecord next;
            while ((next = reader.read()) != null) {
                envelopes.add(fromAvro(next));
            }
        } catch (IOException e) {
            throw new BronzeStorageException(Operation.READ, sourceId, dataFile.toString(),
                    "could not read batch " + receipt.batchId(), e);
        } catch (IllegalStateException e) {
            // RawEnvelope.restored raises this when a stored hash no longer matches its payload.
            throw new BronzeStorageException(Operation.INTEGRITY, sourceId, dataFile.toString(),
                    e.getMessage(), e);
        }
        if (envelopes.size() != receipt.recordCount()) {
            throw new BronzeStorageException(Operation.INTEGRITY, sourceId, dataFile.toString(),
                    "batch %s declares %d record(s) but holds %d"
                            .formatted(receipt.batchId(), receipt.recordCount(), envelopes.size()));
        }
        return envelopes;
    }

    @Override
    public List<String> sources() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(root)) {
            return entries.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new BronzeStorageException(Operation.LIST, null, root.toString(),
                    "could not list sources", e);
        }
    }

    @Override
    public void close() {
        // Nothing held open between calls: readers and writers are scoped to a single operation.
    }

    // --- conversion ------------------------------------------------------

    private static GenericRecord toAvro(RawEnvelope envelope) {
        GenericRecord record = new GenericData.Record(ENVELOPE_SCHEMA);
        record.put("envelopeId", envelope.envelopeId());
        record.put("sourceId", envelope.sourceId());
        record.put("connectorInstanceId", envelope.connectorInstanceId());
        record.put("ingestTimestamp", envelope.ingestTimestamp().toString());
        record.put("sourceAssertedTimestamp",
                envelope.sourceAssertedTimestamp().map(Instant::toString).orElse(null));
        record.put("payload", ByteBuffer.wrap(envelope.payload()));
        record.put("contentHash", envelope.contentHash().toString());
        record.put("offset", envelope.offset().value());
        return record;
    }

    private static RawEnvelope fromAvro(GenericRecord record) {
        ByteBuffer buffer = (ByteBuffer) record.get("payload");
        byte[] payload = new byte[buffer.remaining()];
        buffer.duplicate().get(payload);

        Object asserted = record.get("sourceAssertedTimestamp");
        return RawEnvelope.restored(
                text(record, "envelopeId"),
                text(record, "sourceId"),
                text(record, "connectorInstanceId"),
                Instant.parse(text(record, "ingestTimestamp")),
                asserted == null ? null : Instant.parse(asserted.toString()),
                payload,
                ContentHash.parse(text(record, "contentHash")),
                SourceOffset.of(text(record, "offset")));
    }

    private static String text(GenericRecord record, String field) {
        Object value = record.get(field);
        if (value == null) {
            throw new IllegalStateException("Bronze record is missing required field '" + field + "'");
        }
        return value.toString();
    }

    private String relative(Path path) {
        try {
            return root.relativize(path).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            throw new UncheckedIOException(new IOException("Path " + path + " is outside bronze root " + root, e));
        }
    }
}
