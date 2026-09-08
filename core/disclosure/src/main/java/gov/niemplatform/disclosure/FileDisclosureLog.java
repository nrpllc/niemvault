package gov.niemplatform.disclosure;

import gov.niemplatform.canonical.meta.CanonicalId;
import gov.niemplatform.canonical.meta.TenantId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A disclosure log as a file that is only ever appended to.
 *
 * <p>One record per line, so the file can be read by anything, tailed while it is being written, and
 * checked by a person without the platform present. An auditor should not need this software to read
 * its audit log.
 *
 * <p>Every append is flushed to disk before it returns. That is slower than buffering and it is the
 * whole point: a record still in a buffer when the process dies is a disclosure with no record of it,
 * and those are precisely the ones that will need explaining.
 */
public final class FileDisclosureLog implements DisclosureLog {

    /** Field separator. Chosen because it cannot appear in any field this log writes. */
    private static final char SEPARATOR = '';

    private final TenantId tenant;
    private final Path file;
    private final ReentrantLock appendLock = new ReentrantLock();

    public FileDisclosureLog(Path file, TenantId tenant) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath();
        this.tenant = Objects.requireNonNull(tenant, "tenant");
        try {
            Files.createDirectories(this.file.getParent());
        } catch (IOException e) {
            throw new DisclosureLogException("Cannot create the disclosure log at " + this.file, e);
        }
    }

    @Override
    public TenantId tenant() {
        return tenant;
    }

    @Override
    public void append(DisclosureRecord record) {
        Objects.requireNonNull(record, "record");
        if (!record.requestedBy().equals(tenant) && !record.respondedBy().equals(tenant)) {
            // A log holds this agency's disclosures. A record naming neither side of this tenant
            // arrived here by mistake, and filing it would misattribute somebody else's disclosure.
            throw new DisclosureLogException(
                    "This log belongs to '" + tenant + "', but the disclosure is between '"
                            + record.requestedBy() + "' and '" + record.respondedBy() + "'");
        }

        appendLock.lock();
        try {
            // SYNC: the write reaches the disk before this returns. A disclosure the process
            // believed it had recorded, and had not, is the failure this log exists to prevent.
            Files.writeString(file, encode(record) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (IOException e) {
            throw new DisclosureLogException("Cannot record the disclosure " + record.disclosureId(), e);
        } finally {
            appendLock.unlock();
        }
    }

    @Override
    public List<DisclosureRecord> between(Instant from, Instant to) {
        return all().stream()
                .filter(record -> !record.at().isBefore(from) && record.at().isBefore(to))
                .toList();
    }

    @Override
    public List<DisclosureRecord> involving(TenantId other) {
        return all().stream()
                .filter(record -> record.requestedBy().equals(other)
                        || record.respondedBy().equals(other))
                .toList();
    }

    /** Everything recorded, oldest first, in the order it was written. */
    public List<DisclosureRecord> all() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank()).map(FileDisclosureLog::decode).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the disclosure log at " + file, e);
        }
    }

    // --- encoding ---------------------------------------------------------

    private static String encode(DisclosureRecord record) {
        List<String> fields = new ArrayList<>(List.of(
                record.disclosureId(),
                record.at().toString(),
                record.requestedBy().value(),
                record.requestingPrincipal(),
                record.respondedBy().value(),
                record.authority(),
                record.purpose(),
                oneLine(record.request()),
                record.outcome().name(),
                record.reason() == null ? "" : oneLine(record.reason()),
                String.join(",", record.disclosed().stream().map(CanonicalId::value).toList()),
                Integer.toString(record.withheld()),
                record.decidedBy()));
        return String.join(String.valueOf(SEPARATOR), fields);
    }

    private static DisclosureRecord decode(String line) {
        String[] parts = line.split(String.valueOf(SEPARATOR), -1);
        if (parts.length != 13) {
            throw new DisclosureLogException(
                    "A disclosure log line has " + parts.length + " fields, expected 13. The log has "
                            + "been altered or truncated, which is itself worth investigating.");
        }
        List<CanonicalId> disclosed = parts[10].isEmpty()
                ? List.of()
                : java.util.Arrays.stream(parts[10].split(",")).map(CanonicalId::of).toList();

        return new DisclosureRecord(
                parts[0], Instant.parse(parts[1]), TenantId.of(parts[2]), parts[3],
                TenantId.of(parts[4]), parts[5], parts[6], parts[7],
                DisclosureOutcome.valueOf(parts[8]), parts[9].isEmpty() ? null : parts[9],
                disclosed, Integer.parseInt(parts[11]), parts[12]);
    }

    /** Flattens a field so one record stays one line, which is what makes the file tailable. */
    private static String oneLine(String value) {
        return value.replace("\r", " ").replace("\n", " ").replace(SEPARATOR, ' ');
    }
}
