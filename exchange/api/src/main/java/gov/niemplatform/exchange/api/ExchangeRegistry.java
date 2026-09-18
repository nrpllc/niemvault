package gov.niemplatform.exchange.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Discovers exchange writers on the classpath (ADR 0034).
 *
 * <p>Service-loader based, exactly as connectors are (§4.3), and for the same reason: a state
 * repository's wire format must be shippable as a jar. That matters most in the air-gapped delivery
 * mode (§6), where adding an exchange cannot mean waiting for a platform release -- which is
 * precisely what the hard-coded criminal history writer required.
 *
 * <p>A duplicate {@link ExchangeType} across two jars is an error rather than last-one-wins. Which
 * implementation you got would otherwise depend on classpath order, and diagnosing that while
 * submissions are being rejected is miserable.
 */
public final class ExchangeRegistry {

    private final Map<ExchangeType, ExchangeWriter> byType;

    private ExchangeRegistry(Map<ExchangeType, ExchangeWriter> byType) {
        this.byType = byType;
    }

    public static ExchangeRegistry discover() {
        return discover(ServiceLoader.load(ExchangeWriter.class));
    }

    public static ExchangeRegistry discover(ServiceLoader<ExchangeWriter> loader) {
        Map<ExchangeType, ExchangeWriter> found = new LinkedHashMap<>();
        List<String> conflicts = new ArrayList<>();
        for (ExchangeWriter writer : loader) {
            ExchangeWriter previous = found.putIfAbsent(writer.type(), writer);
            if (previous != null) {
                conflicts.add("%s is provided by both %s and %s".formatted(
                        writer.type(), previous.getClass().getName(), writer.getClass().getName()));
            }
        }
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException(
                    "Conflicting exchange registrations: " + String.join("; ", conflicts));
        }
        return new ExchangeRegistry(Map.copyOf(found));
    }

    /** Builds a registry from known instances, bypassing discovery. */
    public static ExchangeRegistry of(ExchangeWriter... writers) {
        Map<ExchangeType, ExchangeWriter> found = new LinkedHashMap<>();
        for (ExchangeWriter writer : writers) {
            found.put(writer.type(), writer);
        }
        return new ExchangeRegistry(Map.copyOf(found));
    }

    public Optional<ExchangeWriter> forType(ExchangeType type) {
        return Optional.ofNullable(byType.get(type));
    }

    /** Every wire format this deployment can speak, in discovery order. */
    public List<ExchangeType> availableTypes() {
        return List.copyOf(byType.keySet());
    }

    /**
     * Resolves a definition's writer and configures it.
     *
     * @throws ExchangeDefinitionException naming what this deployment does carry -- an air-gapped
     *     operator needs to know whether they are missing a jar or have misspelled a type
     */
    public ExchangeWriter writerFor(ExchangeDefinition definition) {
        ExchangeWriter writer = forType(definition.type()).orElseThrow(() ->
                new ExchangeDefinitionException(null, List.of(
                        "no exchange writer for type '" + definition.type() + "' is on the "
                                + "classpath. This deployment can submit to: " + availableTypes())));
        writer.configure(definition);
        return writer;
    }

    public boolean isEmpty() {
        return byType.isEmpty();
    }
}
