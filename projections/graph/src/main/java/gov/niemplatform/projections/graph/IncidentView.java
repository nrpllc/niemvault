package gov.niemplatform.projections.graph;

import java.util.List;
import java.util.Optional;

/**
 * One incident, everyone on it, and where those people turn up elsewhere.
 *
 * <p>The question the graph projection exists to answer (§4.6). An incident on its own is a row and
 * needs no graph; the reason it is projected is that the same people recur, and the recurrence is
 * what an investigator is looking at.
 *
 * <p><strong>These records carry record values, and print none of them.</strong> A name, a date of
 * birth and a street address are exactly the data ADR 0015 keeps out of {@code toString} — the
 * obligation is inherited by every new type carrying values, and the compiler does not enforce it. A
 * view assembled for an operator is if anything more exposed than a canonical record: it is built to
 * be looked at, so it ends up in log lines and exception messages that a record never reaches.
 */
public record IncidentView(Incident incident, List<Involvement> involvements, List<Connection> connections) {

    public IncidentView {
        involvements = List.copyOf(involvements);
        connections = List.copyOf(connections);
    }

    /** People on this incident who also appear on another. The reason this is a graph. */
    public boolean hasConnections() {
        return !connections.isEmpty();
    }

    @Override
    public String toString() {
        return "IncidentView[incident=" + incident.incidentNumber()
                + ", involvements=" + involvements.size()
                + ", connections=" + connections.size() + "]";
    }

    /**
     * The incident itself.
     *
     * <p>{@code incidentNumber} is printable and the rest is not. It is the agency's own case
     * reference, the thing an operator types to ask for this view and quotes when they report on
     * it; redacting the identifier you were asked about leaves a message that names nothing. The
     * address, the call type and the beat are content.
     */
    public record Incident(
            String canonicalId,
            String incidentNumber,
            String reportedDateTime,
            String locationAddressText,
            String callTypeCode,
            String beat) {

        @Override
        public String toString() {
            return "Incident[incidentNumber=" + incidentNumber + ", canonicalId=" + canonicalId + "]";
        }
    }

    /**
     * A person, as the graph holds them.
     *
     * <p>{@code canonicalId} is the cluster identity, which is platform-assigned and says nothing
     * about the human on its own — it is the handle an operator needs to ask a further question.
     * Everything else here is a record value.
     */
    public record Person(
            String canonicalId,
            String surName,
            String givenName,
            String middleName,
            String birthDate) {

        /** How the person reads on screen. Deliberately a method, so it is never printed by default. */
        public String displayName() {
            String given = givenName == null ? "" : givenName;
            String middle = middleName == null || middleName.isBlank() ? "" : " " + middleName;
            String sur = surName == null ? "" : surName;
            return (given + middle + " " + sur).trim();
        }

        @Override
        public String toString() {
            return "Person[canonicalId=" + canonicalId + ", fields=[surName, givenName, middleName, birthDate]]";
        }
    }

    /** How one person was involved in this incident. */
    public record Involvement(Person person, String involvementCode) {

        @Override
        public String toString() {
            return "Involvement[person=" + person.canonicalId() + ", involvementCode=" + involvementCode + "]";
        }
    }

    /**
     * A person on this incident who also appears on another one.
     *
     * <p>Carries the other incident rather than only its number, because the useful question after
     * "who else was there" is immediately "what was that". Both involvement codes are here: the same
     * person may be a victim on one and a suspect on the other, and that difference is the finding.
     */
    public record Connection(
            Person person,
            String involvementHere,
            Incident other,
            String involvementThere) {

        @Override
        public String toString() {
            return "Connection[person=" + person.canonicalId()
                    + ", other=" + other.incidentNumber()
                    + ", " + involvementHere + "->" + involvementThere + "]";
        }
    }

    /** A listing entry: enough to choose one, not enough to be a substitute for opening it. */
    public record Summary(
            String canonicalId,
            String incidentNumber,
            String reportedDateTime,
            String callTypeCode,
            int people) {

        @Override
        public String toString() {
            return "Summary[incidentNumber=" + incidentNumber + ", people=" + people + "]";
        }
    }

    /** The person on this incident with a given canonical identity, if they are on it. */
    public Optional<Involvement> involvementOf(String canonicalId) {
        return involvements.stream()
                .filter(involvement -> involvement.person().canonicalId().equals(canonicalId))
                .findFirst();
    }
}
