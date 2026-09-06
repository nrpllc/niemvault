package gov.niemplatform.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes events as one JSON object per line through SLF4J.
 *
 * <p>The default sink, and the one that satisfies spec §6: it needs no external service, so it
 * works identically in a managed deployment and in an air-gapped one. Whatever collects
 * container stdout gets structured events rather than prose.
 *
 * <p>Severity maps onto log level so existing operational alerting keeps working, but the
 * payload stays structured -- spec §9 reserves unstructured logs for developer diagnostics.
 */
public final class LoggingObservabilityEmitter implements ObservabilityEmitter {

    private static final Logger LOG = LoggerFactory.getLogger("gov.niemplatform.observability");

    private final ObjectMapper mapper;
    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong();

    public LoggingObservabilityEmitter() {
        this(new ObjectMapper(), Clock.systemUTC());
    }

    public LoggingObservabilityEmitter(ObjectMapper mapper, Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void emit(ObservabilityEvent event) {
        if (event == null) {
            return;
        }
        String line;
        try {
            line = mapper.writeValueAsString(envelope(event));
        } catch (JsonProcessingException e) {
            // A sink that cannot serialise an event must not fail the pipeline that produced it.
            LOG.warn("Could not serialise a {} event: {}", event.type(), e.getOriginalMessage());
            return;
        }
        switch (event.severity()) {
            case ERROR -> LOG.error(line);
            case WARNING -> LOG.warn(line);
            case INFO -> LOG.info(line);
        }
    }

    private Map<String, Object> envelope(ObservabilityEvent event) {
        Instant now = clock.instant();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", "evt-" + sequence.incrementAndGet());
        envelope.put("occurredAt", now.toString());
        envelope.put("type", event.type().name());
        envelope.put("severity", event.severity().name());
        envelope.put("summary", event.summary());
        envelope.putAll(event.attributes());
        return envelope;
    }
}
