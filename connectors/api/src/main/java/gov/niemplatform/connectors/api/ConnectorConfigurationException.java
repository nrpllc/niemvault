package gov.niemplatform.connectors.api;

import java.util.ArrayList;
import java.util.List;

/**
 * A connector was configured with settings it cannot accept.
 *
 * <p>Spec §9: structured, never a bare string, and every problem reported at once so an operator
 * fixes them in one pass rather than one per restart.
 */
public class ConnectorConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** One rejected setting. Carries the key and the reason, never the value. */
    public record Problem(String settingKey, String detail) {

        @Override
        public String toString() {
            return "  '" + settingKey + "': " + detail;
        }
    }

    private final String sourceId;
    private final String connectorInstanceId;
    private final transient List<Problem> problems;

    public ConnectorConfigurationException(String sourceId, String connectorInstanceId, List<Problem> problems) {
        super(render(sourceId, connectorInstanceId, problems));
        this.sourceId = sourceId;
        this.connectorInstanceId = connectorInstanceId;
        this.problems = List.copyOf(problems);
    }

    public String sourceId() {
        return sourceId;
    }

    public String connectorInstanceId() {
        return connectorInstanceId;
    }

    public List<Problem> problems() {
        return problems;
    }

    private static String render(String sourceId, String instanceId, List<Problem> problems) {
        List<String> lines = new ArrayList<>();
        lines.add("Connector '%s' (source '%s') cannot be configured (%d problem%s):"
                .formatted(instanceId, sourceId, problems.size(), problems.size() == 1 ? "" : "s"));
        problems.forEach(problem -> lines.add(problem.toString()));
        return String.join(System.lineSeparator(), lines);
    }
}
