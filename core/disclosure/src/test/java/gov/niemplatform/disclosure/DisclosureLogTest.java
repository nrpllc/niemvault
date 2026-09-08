package gov.niemplatform.disclosure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.canonical.meta.CanonicalId;
import gov.niemplatform.canonical.meta.TenantId;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The record of what crossed an agency boundary.
 *
 * <p>Most of these assert on things that must be impossible rather than things that must work: that
 * a record cannot carry a value, that a refusal cannot be silent, that a release cannot happen
 * without a record. A disclosure log is only worth having if the ways of getting it wrong are closed.
 */
class DisclosureLogTest {

    private static final TenantId COUNTY = TenantId.of("co.riverton.pd");
    private static final TenantId STATE = TenantId.of("st.colorado.cbi");

    @TempDir
    Path work;

    private FileDisclosureLog log;

    private FileDisclosureLog log() {
        if (log == null) {
            log = new FileDisclosureLog(work.resolve("disclosures/log"), COUNTY);
        }
        return log;
    }

    private static DisclosureRecord granted(String id, CanonicalId... records) {
        return new DisclosureRecord(
                id,
                Instant.parse("2026-03-04T18:20:00Z"),
                STATE,
                "det.a.morales@cbi.colorado.gov",
                COUNTY,
                "CRS 24-72-305; CBI-Riverton MOU 2025-11",
                "Homicide investigation 2026-0114; identifying witnesses present at the scene",
                "Person where involvementCode = WITNESS and incident = 2026-000114",
                DisclosureOutcome.GRANTED,
                null,
                List.of(records),
                0,
                "sgt.k.oyelaran@riverton.pd.gov");
    }

    @Nested
    @DisplayName("What a disclosure may contain")
    class Contents {

        @Test
        @DisplayName("it names records and has nowhere to put one")
        void cannotCarryValues() {
            // The enforcement point. An audit log containing the data it audits is a second copy of
            // that data with weaker access controls and longer retention, so the log of who saw what
            // becomes the easiest place to see it.
            assertThat(DisclosureRecord.class.getRecordComponents())
                    .extracting(RecordComponent::getName)
                    .containsExactlyInAnyOrder(
                            "disclosureId", "at", "requestedBy", "requestingPrincipal", "respondedBy",
                            "authority", "purpose", "request", "outcome", "reason", "disclosed",
                            "withheld", "decidedBy");

            assertThat(Stream.of(DisclosureRecord.class.getRecordComponents())
                    .filter(component -> component.getName().equals("disclosed"))
                    .findFirst().orElseThrow().getGenericType().getTypeName())
                    .as("what was disclosed is identities, never records")
                    .contains(CanonicalId.class.getName());
        }

        @Test
        @DisplayName("summarising it prints counts, not identities")
        void summaryHoldsNoIdentities() {
            // Printing identifiers by default puts them into terminal scrollback and screenshots.
            String summary = granted("d-1", CanonicalId.of("co.riverton.pd/person:abc123")).toString();

            assertThat(summary).contains("1 released").doesNotContain("abc123");
        }

        @Test
        @DisplayName("requires the authority it was made under")
        void requiresAuthority() {
            // A disclosure without a stated basis cannot be defended later, and the moment to state
            // it is when it is made rather than when it is questioned.
            assertThatThrownBy(() -> new DisclosureRecord("d", Instant.EPOCH, STATE, "det", COUNTY,
                    "  ", "purpose", "request", DisclosureOutcome.GRANTED, null, List.of(), 0, "sgt"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("authority");
        }

        @Test
        @DisplayName("requires a named person or policy behind the decision")
        void requiresADecider() {
            assertThatThrownBy(() -> new DisclosureRecord("d", Instant.EPOCH, STATE, "det", COUNTY,
                    "statute", "purpose", "request", DisclosureOutcome.GRANTED, null, List.of(), 0, ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("decidedBy");
        }
    }

    @Nested
    @DisplayName("Refusals and partial releases")
    class NotGranted {

        @Test
        @DisplayName("a refusal must say why, so it can be appealed or corrected")
        void refusalNeedsAReason() {
            assertThatThrownBy(() -> new DisclosureRecord("d", Instant.EPOCH, STATE, "det", COUNTY,
                    "statute", "purpose", "request", DisclosureOutcome.REFUSED, null, List.of(), 3, "sgt"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be appealed");
        }

        @Test
        @DisplayName("a refusal that released something is not a refusal")
        void refusalCannotRelease() {
            assertThatThrownBy(() -> new DisclosureRecord("d", Instant.EPOCH, STATE, "det", COUNTY,
                    "statute", "purpose", "request", DisclosureOutcome.REFUSED, "sealed",
                    List.of(CanonicalId.of("co.riverton.pd/person:abc")), 0, "sgt"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("misreport");
        }

        @Test
        @DisplayName("a partial release that withheld nothing is a full one")
        void partialMustWithholdSomething() {
            // An agency told a release was partial reads the absence as a decision. Saying so when
            // nothing was withheld invents a decision nobody made.
            assertThatThrownBy(() -> new DisclosureRecord("d", Instant.EPOCH, STATE, "det", COUNTY,
                    "statute", "purpose", "request", DisclosureOutcome.PARTIAL, "some sealed",
                    List.of(CanonicalId.of("co.riverton.pd/person:abc")), 0, "sgt"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("withheld nothing");
        }

        @Test
        @DisplayName("a refusal is recorded with the same weight as a release")
        void refusalsAreLogged() {
            // A log recording only what was released cannot answer "did anyone try to get this",
            // which is the first question asked when misuse is suspected.
            log().append(new DisclosureRecord("d-refused", Instant.parse("2026-03-05T09:00:00Z"),
                    STATE, "det.morales", COUNTY, "CRS 24-72-305", "background check",
                    "Person where surName = DOE", DisclosureOutcome.REFUSED,
                    "juvenile record; sealed under CRS 19-1-304", List.of(), 2, "sgt.oyelaran"));

            assertThat(log().all()).singleElement()
                    .satisfies(record -> {
                        assertThat(record.outcome()).isEqualTo(DisclosureOutcome.REFUSED);
                        assertThat(record.reason()).contains("sealed");
                        assertThat(record.withheld()).isEqualTo(2);
                    });
        }
    }

    @Nested
    @DisplayName("The log itself")
    class Log {

        @Test
        @DisplayName("offers no way to change or remove a record")
        void isAppendOnly() {
            // Structural, not a matter of who has permissions today. A disclosure log that can be
            // edited is not evidence.
            assertThat(Stream.of(DisclosureLog.class.getMethods()).map(java.lang.reflect.Method::getName))
                    .doesNotContain("delete", "remove", "update", "clear", "truncate");
        }

        @Test
        @DisplayName("survives a restart, because it is a file and not a buffer")
        void isDurable() {
            log().append(granted("d-1", CanonicalId.of("co.riverton.pd/person:abc")));

            var reopened = new FileDisclosureLog(work.resolve("disclosures/log"), COUNTY);
            assertThat(reopened.all()).extracting(DisclosureRecord::disclosureId).containsExactly("d-1");
        }

        @Test
        @DisplayName("is readable without this software, because an auditor will not have it")
        void isPlainText() throws Exception {
            log().append(granted("d-1", CanonicalId.of("co.riverton.pd/person:abc")));

            String raw = Files.readString(work.resolve("disclosures/log"), StandardCharsets.UTF_8);
            assertThat(raw).contains("CRS 24-72-305").contains("GRANTED").contains("Homicide");
            assertThat(raw.lines()).hasSize(1);
        }

        @Test
        @DisplayName("keeps one record on one line, whatever the request contained")
        void survivesMultilineRequests() {
            log().append(new DisclosureRecord("d-multi", Instant.parse("2026-03-04T18:20:00Z"),
                    STATE, "det", COUNTY, "statute", "purpose",
                    "Person where\n  surName = DOE\n  and beat = 3A",
                    DisclosureOutcome.GRANTED, null, List.of(), 0, "sgt"));

            assertThat(log().all()).hasSize(1);
        }

        @Test
        @DisplayName("refuses a disclosure that belongs to neither side of this agency")
        void refusesAForeignDisclosure() {
            // Filing it would misattribute somebody else's disclosure to this agency's log.
            TenantId elsewhere = TenantId.of("st.wyoming.dci");
            assertThatThrownBy(() -> log().append(new DisclosureRecord("d", Instant.EPOCH,
                    elsewhere, "det", TenantId.of("co.denver.pd"), "statute", "purpose", "request",
                    DisclosureOutcome.GRANTED, null, List.of(), 0, "sgt")))
                    .isInstanceOf(DisclosureLogException.class)
                    .hasMessageContaining("belongs to");
        }

        @Test
        @DisplayName("answers the two questions an auditor actually asks")
        void answersAuditQueries() {
            log().append(granted("d-1", CanonicalId.of("co.riverton.pd/person:abc")));
            log().append(new DisclosureRecord("d-2", Instant.parse("2026-04-01T10:00:00Z"),
                    TenantId.of("co.denver.pd"), "det", COUNTY, "MOU", "purpose", "request",
                    DisclosureOutcome.GRANTED, null, List.of(), 0, "sgt"));

            assertThat(log().involving(STATE)).extracting(DisclosureRecord::disclosureId)
                    .containsExactly("d-1");
            assertThat(log().between(
                    Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-04-01T00:00:00Z")))
                    .extracting(DisclosureRecord::disclosureId)
                    .containsExactly("d-1");
        }
    }

    @Nested
    @DisplayName("The ordering that makes the log trustworthy")
    class Ordering {

        @Test
        @DisplayName("the record is written before anything is released")
        void recordsBeforeReleasing() {
            var order = new java.util.ArrayList<String>();
            DisclosureLog watching = new DisclosureLog() {
                @Override
                public TenantId tenant() {
                    return COUNTY;
                }

                @Override
                public void append(DisclosureRecord record) {
                    order.add("recorded");
                }

                @Override
                public List<DisclosureRecord> between(Instant from, Instant to) {
                    return List.of();
                }

                @Override
                public List<DisclosureRecord> involving(TenantId other) {
                    return List.of();
                }
            };

            watching.disclosing(granted("d-1"), () -> {
                order.add("released");
                return "payload";
            });

            assertThat(order).containsExactly("recorded", "released");
        }

        @Test
        @DisplayName("nothing is released when the record cannot be written")
        void refusesToReleaseWhenItCannotRecord() {
            // The consequence worth stating: an agency that cannot write to its own audit log has
            // lost the right to release data until it can, because it can no longer say what it
            // released.
            var released = new java.util.concurrent.atomic.AtomicBoolean(false);
            DisclosureLog failing = new DisclosureLog() {
                @Override
                public TenantId tenant() {
                    return COUNTY;
                }

                @Override
                public void append(DisclosureRecord record) {
                    throw new DisclosureLogException("disk full");
                }

                @Override
                public List<DisclosureRecord> between(Instant from, Instant to) {
                    return List.of();
                }

                @Override
                public List<DisclosureRecord> involving(TenantId other) {
                    return List.of();
                }
            };

            assertThatThrownBy(() -> failing.disclosing(granted("d-1"), () -> {
                released.set(true);
                return "payload";
            })).isInstanceOf(DisclosureLogException.class);

            assertThat(released).isFalse();
        }
    }
}
