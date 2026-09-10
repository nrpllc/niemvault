package gov.niemplatform.modules.le;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Generates a synthetic CAD feed in the shape this module's fixtures describe.
 *
 * <p>Fixtures prove a mapping is right; a feed proves the platform runs. Ten rows in a file exercise
 * every branch of the mapping and none of the things that only appear at rate: a source that keeps
 * arriving, identity resolution meeting the same human on a later incident hours after the first,
 * completeness accounting over many slices, and a schema that drifts underneath a running pipeline.
 *
 * <p><strong>Every person here is invented</strong>, and deliberately so (ADR 0013). None of these
 * names, dates of birth or licence numbers refers to a real human, and the generator holds no path
 * by which real data could reach it -- it is a seeded pseudo-random source and nothing else. A
 * demonstration of a criminal justice platform is exactly the wrong place to improvise with
 * plausible-looking personal data.
 *
 * <h2>Deterministic on purpose</h2>
 *
 * <p>The same seed produces the same feed, byte for byte. A demonstration that produced different
 * records each run could not be checked against an expected result, and a bug that appeared once
 * could not be reproduced.
 *
 * <h2>The mess is the point</h2>
 *
 * <p>A generator that emitted tidy rows would prove the pipeline handles data no agency has. So the
 * feed carries what {@code incidents.csv} carries: names packed as {@code SURNAME, GIVEN M}, one
 * licence written several ways, {@code UNK}/{@code N/A}/{@code NONE} sentinels where a value is
 * missing, an apostrophe that survives in one row and not another, and occasional rows with nothing
 * identifying on them at all.
 */
public final class CadSimulator {

    /** The export's columns, as the mapping declares them. Never read from the feed's own header. */
    public static final String HEADER = "INC_NUM,CALL_TYPE,RPT_DTTM,ADDR,BEAT,ROLE,NAME_FULL,DOB,SEX,DL_NUM";

    private static final ZoneId AGENCY_ZONE = ZoneId.of("America/Denver");
    private static final DateTimeFormatter REPORTED = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");
    private static final DateTimeFormatter REPORTED_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter BORN = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private static final DateTimeFormatter BORN_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Which shape the source is currently exporting. */
    public enum Shape {

        /** What the mapping was written against. */
        CLEAN,

        /**
         * The same source after it changed underneath the mapping.
         *
         * <p>An ISO date of birth, an ISO incident timestamp, and an added column. Every one breaks
         * nothing, passes every other check, and is caught only by a hop contract -- which is the
         * failure §4.2 exists to catch and acceptance criterion 5 exists to prove.
         */
        DRIFTED
    }

    /** One invented human, appearing across several incidents so identity has something to resolve. */
    private record Human(String surname, String given, String middle, String dateOfBirth, String sex,
                         String licence) {}

    /** One generated row, with the parts a transport needs kept separate from the payload. */
    public record Row(String incidentNumber, String csv, Instant reportedAt) {}

    /**
     * A small, fixed cast.
     *
     * <p>Small on purpose: a population of thousands would make every incident a first meeting and
     * identity resolution would resolve nothing. Six humans over many incidents is what produces
     * the repeat encounters an investigator's graph is built out of.
     */
    private static final List<Human> POPULATION = List.of(
            new Human("DOE", "JANE", "M", "03/14/1988", "F", "K447-1902"),
            new Human("NAKAMURA", "HIRO", null, "07/22/1991", "M", null),
            new Human("RIVERA", "LUIS", null, "11/02/1975", "M", null),
            new Human("O'BRIEN", "SEAN", "P", "01/05/1970", "M", "D-9930118"),
            new Human("OKAFOR", "ADA", null, "12/01/1979", "F", "R5540021"),
            new Human("HALE", "MARY", null, "04/02/1966", "F", "T1002993"));

    private static final List<String> CALL_TYPES = List.of("BURG", "ASSLT", "THEFT", "WELCK", "MVA", "DIST");
    private static final List<String> ROLES = List.of("VICT", "SUSP", "WITN", "RP");
    private static final List<String> STREETS = List.of(
            "418 W 9TH ST", "22 ELM AVE", "77 CANYON RD APT 3", "9 OAK ST", "14 BIRCH LN",
            "3 CEDAR CT", "88 SPRUCE WAY", "41 ASPEN DR");
    private static final List<String> BEATS = List.of("1C", "2B", "3A", "4A");
    /** How a missing licence is written. The same absence, spelled three ways, as CAD systems do. */
    private static final List<String> MISSING = List.of("UNK", "N/A", "NONE");

    private final Random random;
    private final Shape shape;
    private final Instant startedAt;
    private final int firstIncidentNumber;

    public CadSimulator(long seed, Shape shape, Instant startedAt) {
        this.random = new Random(seed);
        this.shape = shape;
        this.startedAt = startedAt;
        // Deterministic from the seed, so two runs of the same seed collide in bronze by design --
        // which is what makes the de-duplication story testable rather than theoretical.
        // Kept in the range the fixtures use -- 2026-000114, not 2026-847123 -- so a simulated
        // incident number is indistinguishable in shape from a real one and the demo does not
        // quietly teach an operator the wrong format.
        this.firstIncidentNumber = 100 + new Random(seed).nextInt(800);
    }

    /**
     * An endless feed of rows, grouped by incident.
     *
     * <p>Grouped because a CAD export is denormalised: one row per person per incident, with the
     * incident fields repeated. A generator that emitted one person per incident would never
     * exercise the association hop, which is most of what the canonical model is for.
     */
    public Stream<Row> rows() {
        List<Row> buffer = new ArrayList<>();
        int[] incidentIndex = {0};
        return Stream.generate(() -> {
            if (buffer.isEmpty()) {
                buffer.addAll(incident(incidentIndex[0]++));
            }
            return buffer.removeFirst();
        });
    }

    /** One incident and everyone on it. */
    private List<Row> incident(int index) {
        String incidentNumber = "2026-%06d".formatted(firstIncidentNumber + index);
        String callType = CALL_TYPES.get(random.nextInt(CALL_TYPES.size()));
        String address = STREETS.get(random.nextInt(STREETS.size()));
        String beat = BEATS.get(random.nextInt(BEATS.size()));

        // Incident time advances with the feed rather than tracking the wall clock, so a run
        // replayed later reproduces the same timestamps. Wall-clock ingest time is the envelope's
        // job; this is what the source asserts happened.
        Instant reportedAt = startedAt.plus(Duration.ofMinutes(7L * index));
        LocalDateTime local = LocalDateTime.ofInstant(reportedAt, AGENCY_ZONE);
        String reported = shape == Shape.DRIFTED && random.nextInt(3) == 0
                ? REPORTED_ISO.format(local)
                : REPORTED.format(local);

        int people = 1 + random.nextInt(3);
        List<Row> rows = new ArrayList<>(people);
        for (int person = 0; person < people; person++) {
            String role = ROLES.get(random.nextInt(ROLES.size()));
            // Chosen once and passed down, rather than left in a field for the field-rendering
            // methods to read. The row would still come out right -- Java evaluates arguments left
            // to right -- but it would be right by accident, and reordering two columns would
            // silently attach one person's date of birth to another's name.
            Human human = POPULATION.get(random.nextInt(POPULATION.size()));
            rows.add(new Row(incidentNumber, String.join(",",
                    incidentNumber,
                    callType,
                    reported,
                    quote(address),
                    beat,
                    role,
                    quote(packedName(human)),
                    dateOfBirth(human),
                    human.sex(),
                    licence(human)) + driftedColumn(), reportedAt));
        }
        return rows;
    }

    /** {@code "SURNAME, GIVEN M"}, with the middle initial as often absent as it is in the export. */
    private String packedName(Human human) {
        if (human.middle() == null || random.nextInt(3) == 0) {
            return human.surname() + ", " + human.given();
        }
        return human.surname() + ", " + human.given() + " " + human.middle();
    }

    private String dateOfBirth(Human human) {
        String born = human.dateOfBirth();
        if (shape == Shape.DRIFTED && random.nextInt(3) == 0) {
            // The drift that breaks nothing: a date that is still a date, still parses, still looks
            // right in a spreadsheet, and is silently a different value under the old pattern.
            return BORN_ISO.format(java.time.LocalDate.parse(born, BORN));
        }
        return born;
    }

    /**
     * A licence number, written the several ways one source writes it.
     *
     * <p>The variation is not decoration. A licence with the dash and the same licence without it
     * are the same document, and a resolver that treats them as two people is the failure identity
     * resolution exists to prevent -- which cannot be demonstrated on a feed that spells it one way.
     */
    private String licence(Human human) {
        String licence = human.licence();
        if (licence == null) {
            return MISSING.get(random.nextInt(MISSING.size()));
        }
        return switch (random.nextInt(4)) {
            case 0 -> licence.replace("-", "");
            case 1 -> licence.toLowerCase(java.util.Locale.ROOT);
            default -> licence;
        };
    }

    /** The added column, present only on a drifted feed and only sometimes. */
    private String driftedColumn() {
        if (shape != Shape.DRIFTED || random.nextInt(4) != 0) {
            return "";
        }
        return ",GANG_UNK";
    }

    /**
     * Quotes a free-text field, as the export does.
     *
     * <p>Unconditionally, matching {@code incidents.csv}: the address is quoted there whether or not
     * it happens to contain a comma. A generator that quoted only when it had to would produce a
     * feed subtly unlike the one the mapping was written against.
     */
    private static String quote(String value) {
        return "\"" + value + "\"";
    }
}
