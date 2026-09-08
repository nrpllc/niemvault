package gov.niemplatform.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.niemplatform.projections.graph.IncidentView;
import gov.niemplatform.projections.graph.Neo4jIncidentReader;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Reads an incident and everyone on it back out of the graph (§4.6).
 *
 * <p>What {@code niem inspect} is for bronze, this is for the graph: the command an operator reaches
 * for to see what actually landed, and the first thing that makes the graph projection useful
 * without a Neo4j browser and hand-written Cypher.
 *
 * <h2>Incidents, not cases</h2>
 *
 * <p>Named for what the model holds. A case aggregates incidents and carries a lifecycle; the
 * canonical model has {@code Incident} and no {@code Case}, and calling this {@code niem case} would
 * promise an aggregation that does not exist.
 *
 * <p>The password comes from the environment, as it does for {@code replay}. A password in a
 * command line is in the shell history, in the process list, and in whatever collects either.
 */
@Command(
        name = "incident",
        mixinStandardHelpOptions = true,
        description = "Read an incident, everyone on it, and where they appear elsewhere.")
final class IncidentCommand implements Callable<Integer> {

    /** Environment variable holding the graph password. Shared with {@code replay}. */
    static final String NEO4J_PASSWORD = ReplayCommand.NEO4J_PASSWORD;

    @Parameters(index = "0", arity = "0..1",
            description = "Incident number, as the agency writes it. Omitted: list what is there.")
    String incidentNumber;

    @Option(names = "--neo4j-uri", required = true, description = "Graph projection URI, e.g. bolt://localhost:7687.")
    String neo4jUri;

    @Option(names = "--neo4j-user", defaultValue = "neo4j", description = "Graph user.")
    String neo4jUser;

    @Option(names = "--neo4j-database", description = "Graph database. Omitted: the server's default.")
    String neo4jDatabase;

    @Option(names = "--limit", defaultValue = "20",
            description = "How many incidents to list when no number is given.")
    int limit;

    @Option(names = "--json",
            description = "Emit JSON, for a tool to consume rather than a person to read.")
    boolean json;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Integer call() throws Exception {
        String password = System.getenv(NEO4J_PASSWORD);
        if (password == null || password.isBlank()) {
            System.err.println("Set " + NEO4J_PASSWORD + " to the graph password. It is read from the "
                    + "environment rather than taken as an option: a password on a command line is "
                    + "in the shell history and the process list.");
            return 2;
        }

        try (Neo4jIncidentReader reader =
                new Neo4jIncidentReader(neo4jUri, neo4jUser, password, neo4jDatabase)) {
            return incidentNumber == null ? list(reader) : show(reader);
        }
    }

    /** What is in the graph at all. The first question when nothing is known yet. */
    private int list(Neo4jIncidentReader reader) throws Exception {
        List<IncidentView.Summary> incidents = reader.incidents(limit);

        if (json) {
            ObjectNode root = mapper.createObjectNode();
            root.put("total", reader.incidentCount());
            ArrayNode array = root.putArray("incidents");
            incidents.forEach(summary -> {
                ObjectNode node = array.addObject();
                node.put("incidentNumber", summary.incidentNumber());
                node.put("canonicalId", summary.canonicalId());
                node.put("reportedDateTime", summary.reportedDateTime());
                node.put("callTypeCode", summary.callTypeCode());
                node.put("people", summary.people());
            });
            System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            return 0;
        }

        if (incidents.isEmpty()) {
            // Distinguished from "not found": an empty graph is a pipeline that has not run, and
            // sending an operator to look for a typo in that case wastes their time.
            System.out.println("The graph holds no incidents. Run the pipeline, or check --neo4j-uri.");
            return 0;
        }

        System.out.printf("%d incident(s) in the graph, %d shown, most recent first.%n%n",
                reader.incidentCount(), incidents.size());
        System.out.printf("%-16s %-26s %-8s %s%n", "INCIDENT", "REPORTED", "CALL", "PEOPLE");
        for (IncidentView.Summary summary : incidents) {
            System.out.printf("%-16s %-26s %-8s %d%n",
                    summary.incidentNumber(),
                    orDash(summary.reportedDateTime()),
                    orDash(summary.callTypeCode()),
                    summary.people());
        }
        return 0;
    }

    /** One incident, everyone on it, and where those people turn up elsewhere. */
    private int show(Neo4jIncidentReader reader) throws Exception {
        Optional<IncidentView> found = reader.incident(incidentNumber);

        if (found.isEmpty()) {
            // Exit 1: a scheduled job asking about an incident that is not there has learned
            // something, and a zero exit would let it report the check as clean.
            System.err.println("No incident " + incidentNumber + " in the graph. "
                    + reader.incidentCount() + " incident(s) are there; "
                    + "`niem incident --neo4j-uri " + neo4jUri + "` lists them.");
            return 1;
        }
        IncidentView view = found.get();

        if (json) {
            System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(asJson(view)));
            return 0;
        }

        IncidentView.Incident incident = view.incident();
        System.out.printf("%s  %s%n", incident.incidentNumber(), orDash(incident.callTypeCode()));
        System.out.printf("  reported  %s%n", orDash(incident.reportedDateTime()));
        System.out.printf("  address   %s%n", orDash(incident.locationAddressText()));
        System.out.printf("  beat      %s%n", orDash(incident.beat()));
        System.out.printf("  identity  %s%n", incident.canonicalId());

        System.out.printf("%nInvolved (%d)%n", view.involvements().size());
        for (IncidentView.Involvement involvement : view.involvements()) {
            System.out.printf("  %-14s %-28s %s%n",
                    orDash(involvement.involvementCode()),
                    involvement.person().displayName(),
                    involvement.person().canonicalId());
        }

        if (!view.hasConnections()) {
            System.out.printf("%nNobody on this incident appears on another.%n");
            return 0;
        }

        // The reason the graph exists. An incident on its own is a row; the recurrence is the
        // finding, and both roles are shown because victim-here and suspect-there is the thing an
        // investigator is looking for.
        System.out.printf("%nAlso appear on (%d)%n", view.connections().size());
        for (IncidentView.Connection connection : view.connections()) {
            System.out.printf("  %-28s %-16s %s -> %s%n",
                    connection.person().displayName(),
                    connection.other().incidentNumber(),
                    orDash(connection.involvementHere()),
                    orDash(connection.involvementThere()));
        }
        return 0;
    }

    private ObjectNode asJson(IncidentView view) {
        ObjectNode root = mapper.createObjectNode();

        ObjectNode incident = root.putObject("incident");
        incident.put("incidentNumber", view.incident().incidentNumber());
        incident.put("canonicalId", view.incident().canonicalId());
        incident.put("reportedDateTime", view.incident().reportedDateTime());
        incident.put("locationAddressText", view.incident().locationAddressText());
        incident.put("callTypeCode", view.incident().callTypeCode());
        incident.put("beat", view.incident().beat());

        ArrayNode involvements = root.putArray("involved");
        for (IncidentView.Involvement involvement : view.involvements()) {
            ObjectNode node = involvements.addObject();
            node.put("involvementCode", involvement.involvementCode());
            person(node.putObject("person"), involvement.person());
        }

        ArrayNode connections = root.putArray("connections");
        for (IncidentView.Connection connection : view.connections()) {
            ObjectNode node = connections.addObject();
            person(node.putObject("person"), connection.person());
            node.put("involvementHere", connection.involvementHere());
            node.put("incidentNumber", connection.other().incidentNumber());
            node.put("involvementThere", connection.involvementThere());
        }
        return root;
    }

    private void person(ObjectNode node, IncidentView.Person person) {
        node.put("canonicalId", person.canonicalId());
        node.put("surName", person.surName());
        node.put("givenName", person.givenName());
        node.put("middleName", person.middleName());
        node.put("birthDate", person.birthDate());
    }

    /** An absent value reads as absent. Printing an empty column looks like a formatting bug. */
    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
