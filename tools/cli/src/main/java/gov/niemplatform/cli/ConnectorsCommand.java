package gov.niemplatform.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.niemplatform.connectors.api.ConnectorRegistry;
import gov.niemplatform.connectors.api.ConnectorType;
import gov.niemplatform.connectors.api.SourceConnector;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Reports the transports this deployment can read (spec §4.3).
 *
 * <p>Connectors are discovered through the {@link java.util.ServiceLoader}, which is what lets an
 * agency add a transport by shipping a jar rather than by rebuilding the platform. The cost of that
 * is that the set is not written down anywhere: nothing in the source tree lists it, and an operator
 * asking "can this deployment read a topic?" had to inspect a classpath to find out. In an
 * air-gapped delivery (§6) that question is asked of an installed artifact, by someone who cannot
 * simply rebuild it to see.
 *
 * <p>So the registry answers for itself. This command exists to make the SPI visible, and it is
 * deliberately a report: it configures nothing, opens nothing, and reaches no source.
 *
 * <p>Exit 0 when the deployment has at least one transport, 1 when it has none -- a platform that
 * can read nothing cannot land anything, and that is a broken installation rather than an empty
 * list.
 */
@Command(
        name = "connectors",
        mixinStandardHelpOptions = true,
        description = "Report the transports this deployment can read.")
final class ConnectorsCommand implements Callable<Integer> {

    @Option(names = "--json",
            description = "Emit JSON, for a governance tool to consume rather than a person to read.")
    boolean asJson;

    /**
     * What a transport can say about itself before any source has been described.
     *
     * @param type the id a source definition's {@code type:} field must name
     * @param interactionMode how records arrive, which is a property of the transport
     * @param retention whether records may be kept, where the transport decides it; empty where
     *     the source does
     */
    private record Transport(
            ConnectorType type, String interactionMode, Optional<String> retention) {

        /** Who answers the retention question for this transport (ADR 0027). */
        String retentionDecidedBy() {
            return retention.isPresent() ? "transport" : "source";
        }
    }

    @Override
    public Integer call() {
        ConnectorRegistry registry = ConnectorRegistry.discover();

        List<Transport> transports = new ArrayList<>();
        for (ConnectorType type : registry.availableTypes()) {
            registry.forType(type).map(ConnectorsCommand::describe).ifPresent(transports::add);
        }
        if (asJson) {
            return reportAsJson(transports);
        }
        return report(transports);
    }

    /**
     * Asks a transport what it is, without configuring it.
     *
     * <p>A connector that will not state a retention posture until it has a source in front of it
     * is not broken and is not unknown -- it is the ADR 0027 case, where the same broker carries an
     * agency's own feed and a state system's non-retainable responses. Reporting that as {@code
     * RETAINED}, or as a blank, would both be answering a legal question the connector deliberately
     * refused to answer. It is reported as belonging to the source, which is where it is answered.
     */
    private static Transport describe(SourceConnector connector) {
        Optional<String> retention;
        try {
            retention = Optional.of(connector.retention().name());
        } catch (IllegalStateException decidedPerSource) {
            retention = Optional.empty();
        }
        return new Transport(connector.type(), connector.interactionMode().name(), retention);
    }

    private int report(List<Transport> transports) {
        if (transports.isEmpty()) {
            System.err.println("No connectors are on the classpath. This deployment cannot read any "
                    + "source at all, which is a packaging fault rather than a configuration one: a "
                    + "connector is discovered through its META-INF/services registration (§4.3).");
            return 1;
        }

        System.out.println("Transports this deployment can read:");
        System.out.println();
        System.out.printf("  %-12s %-6s %s%n", "TYPE", "MODE", "RETENTION");
        for (Transport transport : transports) {
            System.out.printf("  %-12s %-6s %s%n",
                    transport.type().id(),
                    transport.interactionMode(),
                    // "per source" rather than a guess. See describe().
                    transport.retention().orElse("per source"));
        }
        System.out.println();
        System.out.printf("%d transport(s). A source names one in the 'type:' field of its source "
                + "definition;%nniem validate --module <dir> checks that the name resolves and its "
                + "settings are usable.%n", transports.size());
        return 0;
    }

    private int reportAsJson(List<Transport> transports) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode report = mapper.createObjectNode();
        ArrayNode array = report.putArray("transports");
        for (Transport transport : transports) {
            ObjectNode node = array.addObject();
            node.put("type", transport.type().id());
            node.put("interactionMode", transport.interactionMode());
            // Null rather than a string, so a consumer cannot read a placeholder as a declaration.
            transport.retention().ifPresentOrElse(
                    posture -> node.put("retention", posture),
                    () -> node.putNull("retention"));
            node.put("retentionDecidedBy", transport.retentionDecidedBy());
        }
        try {
            System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            System.err.println("Could not render the report: " + e.getMessage());
            return 1;
        }
        return transports.isEmpty() ? 1 : 0;
    }
}
