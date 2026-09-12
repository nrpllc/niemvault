package gov.niemplatform.connectors.pull;

import java.util.Locale;
import java.util.Optional;

/**
 * How a pulled file is cut into records.
 *
 * <p>The same two answers the file-drop connector has, and deliberately the same names: a source
 * that moves from a nightly drop to an SFTP pull should not have its record boundaries change along
 * with its transport, because the mapping written against it did not change.
 *
 * <p>Cutting is as far as a connector goes. Parsing the CSV, splitting a packed field, normalising
 * a date -- that is schema mapping, the other half of source onboarding (spec §4.3), and keeping it
 * out is what lets one mapping serve the same records over any transport.
 */
public enum RecordMode {

    /** One record per line, the usual shape for an export. */
    LINE,

    /** One record per file, for a format that is a single document. */
    FILE;

    public static Optional<RecordMode> parse(String declared) {
        return switch (declared.toLowerCase(Locale.ROOT)) {
            case "line" -> Optional.of(LINE);
            case "file" -> Optional.of(FILE);
            default -> Optional.empty();
        };
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
