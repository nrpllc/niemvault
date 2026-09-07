package gov.niemplatform.runtime.engine;

import gov.niemplatform.runtime.transforms.DelimitedRecordDecoder;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * How a landed payload becomes a source-shaped record.
 *
 * <p>Column names are declared here rather than read from a header row. A header is a value in
 * the data: trusting it means a source that reorders or renames its columns silently remaps every
 * field, with no error anywhere. Declaring them turns that into a contract violation.
 *
 * @param format decoder format; {@code delimited} is the only one Phase 1 supports
 * @param emitsType type name given to the decoded record, e.g. {@code source:cad-csv/incident}
 * @param columns column names in source order
 * @param delimiter field separator
 * @param quote quote character
 * @param charset payload encoding
 * @param trimValues whether to trim each decoded value
 */
public record DecoderSpec(
        String format,
        String emitsType,
        List<String> columns,
        char delimiter,
        char quote,
        String charset,
        boolean trimValues) implements Serializable {

    /** The only format Phase 1 supports. Others are a Phase 2 concern. */
    public static final String FORMAT_DELIMITED = "delimited";

    public DecoderSpec {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(emitsType, "emitsType");
        columns = List.copyOf(columns);
        Objects.requireNonNull(charset, "charset");

        if (!FORMAT_DELIMITED.equals(format)) {
            throw new IllegalArgumentException(
                    "Unsupported decoder format '" + format + "'; Phase 1 supports only '"
                            + FORMAT_DELIMITED + "'");
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("A decoder must declare its columns");
        }
        Charset.forName(charset);
    }

    /** A comma-separated, double-quoted, UTF-8 decoder with values trimmed. */
    public static DecoderSpec csv(String emitsType, List<String> columns) {
        return new DecoderSpec(FORMAT_DELIMITED, emitsType, columns, ',', '"', "UTF-8", true);
    }

    /** Builds the decoder this spec describes. */
    public DelimitedRecordDecoder build() {
        return new DelimitedRecordDecoder(
                emitsType, columns, delimiter, quote, Charset.forName(charset), trimValues);
    }

    static Charset defaultCharset() {
        return StandardCharsets.UTF_8;
    }
}
