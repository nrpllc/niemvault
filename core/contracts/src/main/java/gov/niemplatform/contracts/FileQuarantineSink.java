package gov.niemplatform.contracts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A quarantine sink that writes to a file.
 *
 * <p>The in-memory sink is fine for a test or a single-process run, but a distributed run cannot
 * use it: a Flink operator holds its own copy of whatever the job graph shipped it, so records
 * quarantined inside an operator would be collected somewhere the driver never sees and then
 * discarded when the task finished. Spec §4.2 says a violation must not silently drop the record,
 * and an in-memory sink in a distributed run does exactly that.
 *
 * <p>Writes JSON lines, one per rejected record, <strong>with values intact</strong>. Quarantine is
 * the one place the offending data is kept, so this file belongs wherever bronze belongs and under
 * the same access controls — never in a log directory. See ADR 0015.
 *
 * <p>Serializable by path rather than by handle: the writer is opened lazily inside whichever JVM
 * ends up using it.
 */
public final class FileQuarantineSink implements QuarantineSink {

    private static final long serialVersionUID = 1L;

    private final String directory;
    private final String runId;
    private final AtomicLong sequence = new AtomicLong();

    private transient Writer writer;

    /**
     * @param directory where quarantine files are written; created if absent
     * @param runId identifies this run's file, so two runs never interleave into one
     */
    public FileQuarantineSink(Path directory, String runId) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().toString();
        this.runId = Objects.requireNonNull(runId, "runId");
    }

    /** Where this sink writes. Reported by the CLI so an operator can go and look. */
    public Path file() {
        return Path.of(directory).resolve("quarantine-" + runId + ".jsonl");
    }

    @Override
    public synchronized String quarantine(QuarantinedRecord rejected) {
        String id = "q-" + runId + "-" + sequence.incrementAndGet();
        try {
            append(id, rejected);
        } catch (IOException | UncheckedIOException e) {
            // Must not throw: spec §4.2 rules out a bad record halting the pipeline, and that has
            // to hold even when the sink itself is failing. The identifier marks the record as
            // lost so the violation event says so rather than pointing at a file that has nothing.
            return id + "-unwritten";
        }
        return id;
    }

    private void append(String id, QuarantinedRecord rejected) throws IOException {
        if (writer == null) {
            Files.createDirectories(Path.of(directory));
            writer = Files.newBufferedWriter(file(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        writer.write(toJsonLine(id, rejected));
        writer.write(System.lineSeparator());
        // Flushed per record rather than buffered: a run that dies mid-batch must still leave the
        // records it had already rejected, which is the whole point of writing them down.
        writer.flush();
    }

    /**
     * Minimal JSON, hand-written to keep this module free of a serialisation dependency.
     *
     * <p>Record values are rendered with {@code String.valueOf}, which is lossy for a value whose
     * type matters. That is acceptable here and nowhere else: a quarantined record is read by a
     * human deciding what went wrong, and the authoritative copy of the data is still in bronze.
     */
    private String toJsonLine(String id, QuarantinedRecord rejected) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("quarantineId", id);
        document.put("runId", runId);
        document.put("contract", rejected.contractId().toString());
        document.put("hop", rejected.hopId());
        document.put("direction", rejected.direction().name());
        document.put("sourceId", rejected.context().sourceId());
        document.put("recordType", rejected.record().typeName());

        StringBuilder json = new StringBuilder("{");
        document.forEach((key, value) -> json.append(quote(key)).append(':')
                .append(quote(String.valueOf(value))).append(','));

        json.append(quote("failures")).append(":[");
        for (int i = 0; i < rejected.failures().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(quote(rejected.failures().get(i).toString()));
        }
        json.append("],");

        json.append(quote("values")).append(":{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : rejected.record().values().entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(quote(entry.getKey())).append(':')
                    .append(entry.getValue() == null ? "null" : quote(String.valueOf(entry.getValue())));
        }
        json.append("}}");
        return json.toString();
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
