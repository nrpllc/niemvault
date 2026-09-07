package gov.niemplatform.controlplane.advice;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.observability.ValueShape;
import gov.niemplatform.runtime.engine.MappingDefinition;
import gov.niemplatform.runtime.transforms.DelimitedRecordDecoder;
import gov.niemplatform.storage.api.BronzeRange;
import gov.niemplatform.storage.api.BronzeStore;
import gov.niemplatform.storage.api.RawEnvelope;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Observes the shape of each source column, without ever holding a value (ADR 0023).
 *
 * <p>This is what makes a suggestion worth trusting. A column's name says what somebody once meant
 * it to hold; its shape says what the source is actually sending today. Knowing that
 * {@code RPT_DTTM} is always four digits, a slash, two digits, a slash, two digits, a space and
 * {@code HH:mm} is what picks {@code parseDateTime} <em>and</em> its pattern. No name can supply
 * the pattern.
 *
 * <h2>A shape is only reported if it holds across the sample</h2>
 *
 * <p>One row telling us a column looks like a date is a coincidence. Fifty rows agreeing is a fact
 * about the feed. A column whose shape varies — free text, an address, a name — reports no shape at
 * all rather than the shape of whichever row happened to be first, because a wrong pattern
 * confidently proposed is worse than no proposal.
 *
 * <h2>Values do not survive this class</h2>
 *
 * <p>{@link ValueShape} is computed inside the loop and the value is dropped immediately.
 * {@code ###-##-####} leaves; the number does not. That is the whole reason the advisor is allowed
 * to see anything about the data at all.
 */
public final class ColumnProfiler {

    /** Rows to read. Enough for a shape to be a fact rather than a coincidence. */
    private static final int DEFAULT_SAMPLE = 200;

    private final int sampleSize;

    public ColumnProfiler() {
        this(DEFAULT_SAMPLE);
    }

    public ColumnProfiler(int sampleSize) {
        if (sampleSize < 1) {
            throw new IllegalArgumentException("sampleSize must be positive, was " + sampleSize);
        }
        this.sampleSize = sampleSize;
    }

    /**
     * Profiles what has landed for a mapping's source.
     *
     * @return the shape of each column that has one consistently; columns that vary are absent
     */
    public Map<String, ValueShape> profile(BronzeStore bronze, MappingDefinition mapping) {
        // The mapping's own decoder, built the way the pipeline builds it. Reconstructing one here
        // would be a second place that has to agree about delimiters and quoting.
        DelimitedRecordDecoder decoder = mapping.decoder().build();

        Map<String, ValueShape> seen = new LinkedHashMap<>();
        Set<String> varying = new LinkedHashSet<>();
        int read = 0;

        try (Stream<RawEnvelope> envelopes = bronze.read(mapping.sourceId(), BronzeRange.all())) {
            for (RawEnvelope envelope : (Iterable<RawEnvelope>) envelopes::iterator) {
                if (read++ >= sampleSize) {
                    break;
                }
                Record decoded;
                try {
                    decoded = decoder.decode(envelope.payload());
                } catch (RuntimeException undecodable) {
                    // A row the decoder cannot read tells us nothing about the shape of a column.
                    // It is a real problem, but it is the pipeline's to report, not the advisor's.
                    continue;
                }
                for (String column : mapping.decoder().columns()) {
                    observe(seen, varying, column, decoded.raw(column));
                }
            }
        }

        varying.forEach(seen::remove);
        return Map.copyOf(seen);
    }

    private static void observe(Map<String, ValueShape> seen, Set<String> varying,
            String column, Object value) {

        if (varying.contains(column)) {
            return;
        }
        ValueShape shape = ValueShape.of(value);
        if (shape.pattern() == null) {
            // Absent or non-textual. Neither disagrees with a shape already seen, and neither
            // establishes one.
            return;
        }
        ValueShape existing = seen.putIfAbsent(column, shape);
        if (existing != null && !existing.pattern().equals(shape.pattern())) {
            varying.add(column);
        }
    }
}
