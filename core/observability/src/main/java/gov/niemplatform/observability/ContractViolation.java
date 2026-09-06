package gov.niemplatform.observability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A record failed its hop contract (spec §4.2, §4.7).
 *
 * <p>Emitted on both directions of every hop. An input violation means a source changed; an
 * output violation means a mapping is wrong. Both route the record to quarantine, and neither
 * halts the pipeline by default -- a source sending one bad record must not stop the other
 * thousand from landing.
 *
 * @param context where the violation happened, including hop and mapping version
 * @param direction which side of the hop was being validated
 * @param recordTypeName shape the record claimed to be
 * @param failures one entry per failed expectation; a record failing three checks reports three
 * @param quarantineId where the offending record can be found, so the event is actionable
 */
public record ContractViolation(
        PipelineContext context,
        Direction direction,
        String recordTypeName,
        List<Failure> failures,
        String quarantineId) implements ObservabilityEvent {

    /**
     * One failed expectation.
     *
     * @param fieldName field at fault, or {@code null} for a record-level failure
     * @param rule identifier of the expectation that failed, e.g. {@code required} or {@code codeList}
     * @param expected what the contract required, in words
     * @param actual redacted description of what arrived -- never the value itself
     */
    public record Failure(String fieldName, String rule, String expected, ValueShape actual)
            implements java.io.Serializable {

        public Failure {
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(expected, "expected");
            actual = actual == null ? ValueShape.absent() : actual;
        }

        @Override
        public String toString() {
            String where = fieldName == null ? "<record>" : fieldName;
            return "%s: %s (expected %s, found %s)".formatted(where, rule, expected, actual);
        }
    }

    public ContractViolation {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(recordTypeName, "recordTypeName");
        failures = List.copyOf(failures);
        if (failures.isEmpty()) {
            throw new IllegalArgumentException("A contract violation must carry at least one failure");
        }
    }

    @Override
    public EventType type() {
        return EventType.CONTRACT_VIOLATION;
    }

    @Override
    public Severity severity() {
        return Severity.ERROR;
    }

    @Override
    public Map<String, Object> attributes() {
        Map<String, Object> attributes = new LinkedHashMap<>(context.attributes());
        attributes.put("direction", direction.name());
        attributes.put("recordType", recordTypeName);
        attributes.put("failureCount", failures.size());
        attributes.put("failures", failures.stream().map(Failure::toString).toList());
        if (quarantineId != null) {
            attributes.put("quarantineId", quarantineId);
        }
        return attributes;
    }

    @Override
    public String summary() {
        return "%s contract violation at hop '%s' on %s: %s"
                .formatted(direction.name().toLowerCase(java.util.Locale.ROOT),
                        context.hopId(), recordTypeName, failures.getFirst());
    }
}
