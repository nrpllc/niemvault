package gov.niemplatform.runtime.transforms;

import gov.niemplatform.canonical.data.Record;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns a delimited line into a source-shaped record.
 *
 * <p>The first thing that happens to a landed payload. The connector deliberately did not parse
 * (spec §4.3), so this is where bytes become named fields -- and it is still not canonicalisation:
 * every value comes out as text, exactly as the source wrote it, for the hop contract to check and
 * the transforms to interpret.
 *
 * <p>Column names come from the mapping artifact rather than from a header row. A header is a
 * value in the data, and trusting it means a source that reorders or renames its columns silently
 * remaps every field. Declared columns turn that into a contract violation instead.
 *
 * <p>Handles the CSV quoting real exports use: quoted fields containing the delimiter, and doubled
 * quotes inside a quoted field.
 */
public final class DelimitedRecordDecoder implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String typeName;
    private final List<String> columns;
    private final char delimiter;
    private final char quote;
    private final Charset charset;
    private final boolean trimValues;

    public DelimitedRecordDecoder(
            String typeName, List<String> columns, char delimiter, char quote, Charset charset, boolean trimValues) {
        this.typeName = Objects.requireNonNull(typeName, "typeName");
        this.columns = List.copyOf(columns);
        this.delimiter = delimiter;
        this.quote = quote;
        this.charset = Objects.requireNonNull(charset, "charset");
        this.trimValues = trimValues;
        if (this.columns.isEmpty()) {
            throw new IllegalArgumentException("A delimited decoder needs at least one declared column");
        }
    }

    /** A comma-delimited, double-quoted, UTF-8 decoder with values trimmed. */
    public static DelimitedRecordDecoder csv(String typeName, List<String> columns) {
        return new DelimitedRecordDecoder(typeName, columns, ',', '"', StandardCharsets.UTF_8, true);
    }

    public String typeName() {
        return typeName;
    }

    public List<String> columns() {
        return columns;
    }

    /**
     * Decodes one payload.
     *
     * <p>A row with the wrong number of fields is <strong>not</strong> silently padded or
     * truncated. Missing trailing columns are left absent and surplus ones are recorded under
     * generated names, so the hop contract sees a missing required field or an unexpected field
     * and quarantines the record with a violation naming it. Padding here would hide a source
     * changing its column count, which is exactly the drift the contract layer is for.
     */
    public Record decode(byte[] payload) {
        String line = new String(payload, charset);
        List<String> values = split(line);

        Record.Builder builder = Record.builder(typeName);
        for (int i = 0; i < columns.size(); i++) {
            if (i < values.size()) {
                String value = trimValues ? values.get(i).trim() : values.get(i);
                builder.set(columns.get(i), value.isEmpty() ? null : value);
            }
            // Beyond the values present: leave the field absent rather than inventing a null.
        }
        for (int i = columns.size(); i < values.size(); i++) {
            builder.set("column" + (i + 1), values.get(i));
        }
        return builder.build();
    }

    /** Splits one line, honouring quoted fields and doubled quotes. */
    private List<String> split(String line) {
        List<String> values = new ArrayList<>(columns.size());
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == quote) {
                    if (i + 1 < line.length() && line.charAt(i + 1) == quote) {
                        current.append(quote);
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == quote) {
                inQuotes = true;
            } else if (c == delimiter) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values;
    }
}
