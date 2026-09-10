package gov.niemplatform.modules.le;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The simulated feed has to be the same shape as the fixtures, or it proves nothing.
 *
 * <p>A generator that drifted away from {@code incidents.csv} would demonstrate a pipeline handling
 * data no agency sends, which is worse than not demonstrating it at all.
 */
class CadSimulatorTest {

    private static final Instant START = Instant.parse("2026-03-04T18:20:00Z");

    private static List<CadSimulator.Row> feed(long seed, CadSimulator.Shape shape, int rows) {
        try (Stream<CadSimulator.Row> stream = new CadSimulator(seed, shape, START).rows()) {
            return stream.limit(rows).toList();
        }
    }

    @Test
    @DisplayName("the same seed produces the same feed, byte for byte")
    void isDeterministic() {
        // A demonstration that produced different records each run could not be checked against an
        // expected result, and a bug that appeared once could not be reproduced.
        assertThat(feed(42L, CadSimulator.Shape.CLEAN, 50))
                .isEqualTo(feed(42L, CadSimulator.Shape.CLEAN, 50));
        assertThat(feed(43L, CadSimulator.Shape.CLEAN, 50))
                .isNotEqualTo(feed(42L, CadSimulator.Shape.CLEAN, 50));
    }

    @Test
    @DisplayName("a clean row has exactly the columns the mapping declares")
    void matchesTheDeclaredColumns() {
        int columns = CadSimulator.HEADER.split(",").length;

        for (CadSimulator.Row row : feed(7L, CadSimulator.Shape.CLEAN, 200)) {
            assertThat(fieldsOf(row.csv()))
                    .as("row '%s'", row.csv())
                    .hasSize(columns);
        }
    }

    @Test
    @DisplayName("an incident repeats its own fields across the people on it")
    void denormalisesLikeARealExport() {
        List<CadSimulator.Row> rows = feed(11L, CadSimulator.Shape.CLEAN, 200);

        // Rows for one incident are adjacent and agree on every incident-level field. Without that
        // the association hop -- most of what the canonical model is for -- is never exercised.
        for (int index = 1; index < rows.size(); index++) {
            CadSimulator.Row previous = rows.get(index - 1);
            CadSimulator.Row row = rows.get(index);
            if (!row.incidentNumber().equals(previous.incidentNumber())) {
                continue;
            }
            List<String> before = fieldsOf(previous.csv());
            List<String> now = fieldsOf(row.csv());
            assertThat(now.subList(0, 5)).isEqualTo(before.subList(0, 5));
            assertThat(row.reportedAt()).isEqualTo(previous.reportedAt());
        }
        assertThat(rows.stream().map(CadSimulator.Row::incidentNumber).distinct().count())
                .isLessThan(rows.size());
    }

    @Test
    @DisplayName("the feed carries the mess a real export carries")
    void carriesTheMessinessTheMappingWasWrittenFor() {
        String feed = String.join("\n",
                feed(3L, CadSimulator.Shape.CLEAN, 400).stream().map(CadSimulator.Row::csv).toList());

        // Each of these is a branch of the mapping. A tidy feed would leave all of them untested.
        assertThat(feed).as("packed surname-first name").contains(", ");
        assertThat(feed).as("a missing licence, spelled several ways")
                .containsAnyOf("UNK", "N/A", "NONE");
        assertThat(feed).as("an apostrophe in a name").contains("O'BRIEN");
        assertThat(feed).as("one licence written with and without its dash")
                .contains("K447-1902").contains("K4471902");
    }

    @Test
    @DisplayName("a person recurs across incidents, which is what identity resolution is for")
    void bringsTheSamePeopleBack() {
        List<CadSimulator.Row> rows = feed(5L, CadSimulator.Shape.CLEAN, 200);

        long distinctIncidents = rows.stream().map(CadSimulator.Row::incidentNumber).distinct().count();
        long distinctSurnames = rows.stream()
                .map(row -> fieldsOf(row.csv()).get(6).split(",")[0].replace("\"", ""))
                .distinct()
                .count();

        // A population of thousands would make every incident a first meeting and resolve nothing.
        assertThat(distinctSurnames).isLessThanOrEqualTo(6).isLessThan(distinctIncidents);
    }

    @Test
    @DisplayName("a drifted feed changes shape without breaking anything a parser would notice")
    void driftsTheWayARealSourceDrifts() {
        List<CadSimulator.Row> rows = feed(9L, CadSimulator.Shape.DRIFTED, 400);
        String feed = String.join("\n", rows.stream().map(CadSimulator.Row::csv).toList());
        int declaredColumns = CadSimulator.HEADER.split(",").length;

        // An ISO date of birth where the mapping expects MM/dd/yyyy: still a date, still parses,
        // still looks right in a spreadsheet, silently a different value. Only a hop contract
        // catches it, which is what criterion 5 exists to prove.
        assertThat(feed).matches("(?s).*,\\d{4}-\\d{2}-\\d{2},.*");
        assertThat(rows).anySatisfy(row ->
                assertThat(fieldsOf(row.csv())).hasSize(declaredColumns + 1));
        assertThat(rows).anySatisfy(row ->
                assertThat(fieldsOf(row.csv())).hasSize(declaredColumns));
    }

    /** Splits a CSV row on commas outside quotes, which is all this generator can produce. */
    private static List<String> fieldsOf(String csv) {
        List<String> fields = new java.util.ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (char character : csv.toCharArray()) {
            if (character == '"') {
                quoted = !quoted;
                field.append(character);
            } else if (character == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(character);
            }
        }
        fields.add(field.toString());
        return fields;
    }
}
