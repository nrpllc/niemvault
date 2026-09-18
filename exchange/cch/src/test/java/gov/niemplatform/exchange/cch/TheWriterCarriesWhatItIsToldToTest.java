package gov.niemplatform.exchange.cch;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gov.niemplatform.canonical.core.CoreCanonicalTypes;
import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalId;
import gov.niemplatform.canonical.meta.CanonicalRef;
import gov.niemplatform.exchange.api.AssembledDocument;
import gov.niemplatform.exchange.api.AssemblySpec;
import gov.niemplatform.exchange.api.DocumentAssembler;
import gov.niemplatform.exchange.api.ExchangeDefinition;
import gov.niemplatform.exchange.api.ExchangeType;
import gov.niemplatform.exchange.api.SubmissionOutcome;
import gov.niemplatform.exchange.api.SubmissionReceipt;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The writer sends what the exchange assembled, and knows nothing about what that is (ADR 0034).
 *
 * <p>The claim being tested is a negative one, which is why it is tested at all: no canonical type
 * name appears anywhere in {@link CchExchangeWriter} or {@link DocumentJson}. The writer it replaced
 * could carry three types and no criminal history record at all; this one carries an arrest with its
 * charges and their dispositions without a line of code that knows what a charge is.
 *
 * <p>Served over a real socket. A double for HTTP would agree with whatever the writer did, and the
 * things worth catching here -- a token header, a rejection status, a body the receiver has to
 * parse -- only exist on the wire.
 */
class TheWriterCarriesWhatItIsToldToTest {

    private static final Instant NOW = Instant.parse("2026-03-04T11:20:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> received = new AtomicReference<>();
    private final AtomicReference<String> receivedToken = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "{\"receipts\":{}}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/submit", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        receivedToken.set(exchange.getRequestHeaders().getFirst(CchExchangeWriter.HEADER_TOKEN));
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Test
    @DisplayName("a criminal history document reaches the wire with its charges and their outcomes")
    void sendsWhatWasAssembled() throws Exception {
        List<AssembledDocument> documents = assembleCch();
        writer().submit(documents);

        JsonNode sent = JSON.readTree(received.get());
        assertThat(sent.get("exchange").asText()).isEqualTo("fdle-cch-arrest");
        assertThat(sent.get("sourceId").asText()).isEqualTo("leon-so-cad");

        JsonNode arrest = sent.get("documents").get(0);
        assertThat(arrest.get("type").asText()).isEqualTo("Arrest");
        assertThat(arrest.get("fields").get("arrestAgencyRecordId").asText())
                .isEqualTo("LEON-2026-0114");

        JsonNode charge = arrest.get("elements").get("charges").get(0);
        assertThat(charge.get("type").asText()).isEqualTo("Charge");
        assertThat(charge.get("fields").get("statuteCodeId").asText()).isEqualTo("784.03");

        // Two levels down, and the writer has never heard of a Disposition.
        JsonNode disposition = charge.get("elements").get("disposition").get(0);
        assertThat(disposition.get("fields").get("dispositionCategoryCode").asText())
                .isEqualTo("CONVICTED");
    }

    /**
     * An association is not only a pointer.
     *
     * <p>Whether the subject was read their rights is a fact about this arrest and this person, and
     * it lives on the association joining them. A walk that kept only the endpoints would lose it
     * irrecoverably.
     */
    @Test
    @DisplayName("what the relationship itself says crosses with it")
    void carriesAssociationFields() throws Exception {
        writer().submit(assembleCch());

        JsonNode subject = JSON.readTree(received.get())
                .get("documents").get(0).get("elements").get("subject").get(0);

        assertThat(subject.get("type").asText()).isEqualTo("Person");
        assertThat(subject.get("via").get("type").asText()).isEqualTo("ArrestSubjectAssociation");
        assertThat(subject.get("via").get("fields").get("rightsReadIndicator").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("values keep their types, so a receiver need not guess what a date is")
    void valuesAreTyped() throws Exception {
        writer().submit(assembleCch());

        JsonNode sent = JSON.readTree(received.get());
        JsonNode arrestFields = sent.get("documents").get(0).get("fields");
        assertThat(arrestFields.get("arrestDateTime").asText()).isEqualTo("2026-03-04T11:20:00Z");

        JsonNode dispositionFields = sent.get("documents").get(0)
                .get("elements").get("charges").get(0)
                .get("elements").get("disposition").get(0).get("fields");
        assertThat(dispositionFields.get("dispositionDate").asText()).isEqualTo("2026-06-01");

        JsonNode subjectVia = sent.get("documents").get(0)
                .get("elements").get("subject").get(0).get("via").get("fields");
        assertThat(subjectVia.get("rightsReadIndicator").isBoolean()).isTrue();
    }

    /**
     * A repository accepts and rejects individually, so one bad charge must not make an operator
     * resubmit forty arrests that were already posted.
     */
    @Test
    @DisplayName("a receipt per document, not one for the batch")
    void receiptsArePerDocument() {
        responseBody = """
                {"receipts": {
                  "A1": {"outcome": "accepted", "remoteId": "TCN-0001"},
                  "A2": {"outcome": "rejected", "reason": "statute 999.99 is not recognised"}
                }}""";

        List<AssembledDocument> documents = assembleTwoArrests();
        List<SubmissionReceipt> receipts = writer().submit(documents);

        assertThat(receipts).hasSize(2);
        assertThat(receipts.getFirst().outcome()).isEqualTo(SubmissionOutcome.ACCEPTED);
        assertThat(receipts.getFirst().remoteIdentifier()).contains("TCN-0001");
        assertThat(receipts.get(1).outcome()).isEqualTo(SubmissionOutcome.REJECTED);
        assertThat(receipts.get(1).detail()).contains("999.99");
    }

    /**
     * Silence is not success.
     *
     * <p>A fingerprint-backed entry is answered days later against the identifier issued now.
     * Reporting an unanswered document as accepted would claim a criminal history entry that may
     * still be refused.
     */
    @Test
    @DisplayName("a document the repository said nothing about is pending, never accepted")
    void silenceIsPending() {
        responseBody = "{\"receipts\":{}}";

        List<SubmissionReceipt> receipts = writer().submit(assembleCch());

        assertThat(receipts).singleElement()
                .satisfies(receipt -> assertThat(receipt.outcome())
                        .isEqualTo(SubmissionOutcome.PENDING));
    }

    @Test
    @DisplayName("a refusal is reported against every document in the send, with what was said")
    void refusalsAreReported() {
        status = 422;
        responseBody = "the submitting ORI is not authorised for this county";

        List<SubmissionReceipt> receipts = writer().submit(assembleCch());

        assertThat(receipts).singleElement().satisfies(receipt -> {
            assertThat(receipt.outcome()).isEqualTo(SubmissionOutcome.REJECTED);
            assertThat(receipt.detail()).contains("422").contains("not authorised");
        });
    }

    @Test
    @DisplayName("the token comes from the environment by name, and never from the artifact")
    void tokenIsNotInTheArtifact() {
        writer().submit(assembleCch());

        // No tokenEnv is set in this test's environment, so nothing is sent -- and the definition
        // itself never held a token to leak.
        assertThat(receivedToken.get()).isNull();
        assertThat(definition().settings()).doesNotContainKey("token");
    }

    @Test
    @DisplayName("a writer prints its endpoint and never its credential")
    void printsNoSecret() {
        assertThat(writer().toString())
                .contains("/submit")
                .doesNotContain("secret");
    }

    // --- harness --------------------------------------------------------------------------------

    private CchExchangeWriter writer() {
        CchExchangeWriter writer =
                new CchExchangeWriter(HttpClient.newHttpClient(), Clock.fixed(NOW, ZoneOffset.UTC));
        writer.configure(definition());
        return writer;
    }

    private ExchangeDefinition definition() {
        return new ExchangeDefinition(
                "fdle-cch-arrest", "1.0.0", ExchangeType.of("cch-http"), "leon-so-cad",
                CCH_ASSEMBLY,
                Map.of("endpoint",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/submit"));
    }

    private static final AssemblySpec CCH_ASSEMBLY = new AssemblySpec("Arrest", List.of(
            new AssemblySpec.Follow("ArrestSubjectAssociation", "person", "subject", List.of()),
            new AssemblySpec.Follow("ArrestChargeAssociation", "charge", "charges", List.of(
                    new AssemblySpec.Follow(
                            "ChargeDispositionAssociation", "disposition", "disposition",
                            List.of())))));

    private List<AssembledDocument> assembleCch() {
        List<Record> silver = new ArrayList<>(List.of(
                arrest("A1", "LEON-2026-0114"),
                person("P1"),
                charge("C1", "784.03"),
                disposition("D1"),
                rightsRead("ASA1", "A1", "P1"),
                link("ArrestChargeAssociation", "ACA1", "arrest", "Arrest", "A1", "charge", "Charge", "C1"),
                link("ChargeDispositionAssociation", "CDA1", "charge", "Charge", "C1",
                        "disposition", "Disposition", "D1")));
        return new DocumentAssembler(CoreCanonicalTypes.ALL)
                .assemble(CCH_ASSEMBLY, silver).documents();
    }

    private List<AssembledDocument> assembleTwoArrests() {
        List<Record> silver = List.of(arrest("A1", "LEON-2026-0114"), arrest("A2", "LEON-2026-0200"));
        return new DocumentAssembler(CoreCanonicalTypes.ALL)
                .assemble(CCH_ASSEMBLY, silver).documents();
    }

    private static Record arrest(String id, String recordNumber) {
        return Record.builder("Arrest")
                .set("canonicalId", CanonicalId.of(id))
                .set("arrestAgencyRecordId", recordNumber)
                .set("arrestDateTime", NOW)
                .set("arrestAgencyName", "Leon County SO")
                .build();
    }

    private static Record person(String id) {
        return Record.builder("Person")
                .set("canonicalId", CanonicalId.of(id))
                .set("surName", "CHEN")
                .set("birthDate", LocalDate.of(1988, 3, 14))
                .build();
    }

    private static Record charge(String id, String statute) {
        return Record.builder("Charge")
                .set("canonicalId", CanonicalId.of(id))
                .set("chargeTrackingId", "TRK-" + id)
                .set("statuteCodeId", statute)
                .set("countQuantity", 2L)
                .build();
    }

    private static Record disposition(String id) {
        return Record.builder("Disposition")
                .set("canonicalId", CanonicalId.of(id))
                .set("dispositionId", "DISP-" + id)
                .set("dispositionDate", LocalDate.of(2026, 6, 1))
                .set("dispositionCategoryCode", "CONVICTED")
                .set("reportingAuthorityText", "Leon County Clerk of Court")
                .build();
    }

    private static Record rightsRead(String id, String arrestId, String personId) {
        return Record.builder("ArrestSubjectAssociation")
                .set("canonicalId", CanonicalId.of(id))
                .set("arrest", CanonicalRef.to("Arrest", arrestId))
                .set("person", CanonicalRef.to("Person", personId))
                .set("rightsReadIndicator", Boolean.TRUE)
                .build();
    }

    private static Record link(
            String associationType, String id,
            String roleA, String typeA, String idA,
            String roleB, String typeB, String idB) {
        return Record.builder(associationType)
                .set("canonicalId", CanonicalId.of(id))
                .set(roleA, CanonicalRef.to(typeA, idA))
                .set(roleB, CanonicalRef.to(typeB, idB))
                .build();
    }
}
