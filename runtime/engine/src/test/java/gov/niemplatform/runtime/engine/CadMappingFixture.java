package gov.niemplatform.runtime.engine;

import gov.niemplatform.canonical.core.CoreCanonicalTypes;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.canonical.meta.FieldType;
import gov.niemplatform.contracts.ContractId;
import gov.niemplatform.contracts.FieldExpectation;
import gov.niemplatform.contracts.HopContract;
import gov.niemplatform.contracts.Schema;
import gov.niemplatform.contracts.SchemaHopContract;
import gov.niemplatform.identity.api.ClusterId;
import gov.niemplatform.identity.api.EntityAttributes;
import gov.niemplatform.identity.api.MatchEvidence;
import gov.niemplatform.identity.api.ProviderCapabilities;
import gov.niemplatform.identity.api.ResolutionKey;
import gov.niemplatform.identity.api.ResolutionProvider;
import gov.niemplatform.identity.api.ResolutionResult;
import gov.niemplatform.runtime.transforms.TransformSpec;
import gov.niemplatform.storage.api.RawEnvelope;
import gov.niemplatform.storage.api.SourceOffset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A realistic CAD mapping, shared by the engine tests.
 *
 * <p>One denormalised export row carries an incident and one involved person, which is the common
 * shape of a CAD extract. It maps to three canonical records -- an {@code Incident}, a
 * {@code Person}, and the association between them -- which is the whole of the Phase 1 canonical
 * scope, exercised end to end.
 *
 * <p>The fixture rows carry the messiness ADR 0013 insists on: a packed {@code LAST, FIRST M}
 * name, a licence number with punctuation, an agency sentinel standing in for null, and dispatch
 * codes in the agency's own vocabulary.
 */
final class CadMappingFixture {

    static final String SOURCE_ID = "riverton-pd-cad";
    static final String SOURCE_TYPE = "source:cad-csv/incident-person";
    static final String CONNECTOR = "file-drop-01";
    static final Instant INGEST = Instant.parse("2026-03-04T17:25:00Z");

    static final List<String> COLUMNS = List.of(
            "INC_NUM", "CALL_TYPE", "RPT_DTTM", "ADDR", "BEAT",
            "ROLE", "NAME_FULL", "DOB", "SEX", "DL_NUM");

    private CadMappingFixture() {}

    // --- source data -----------------------------------------------------

    /** Jane Doe, victim of a burglary. */
    static final String ROW_BURGLARY_VICTIM =
            "2026-000114,BURG,2026/03/04 11:20,\"418 W 9TH ST\",3A,VICT,\"DOE, JANE M\",03/14/1988,F,K447-1902";

    /** The same human, different incident, licence written differently. Criterion 3 rests on this. */
    static final String ROW_SAME_PERSON_LATER =
            "2026-000210,THEFT,2026/03/06 09:05,\"1200 MAIN ST\",2B,WITN,\"DOE, JANE\",03/14/1988,F,K4471902";

    /** A different human, with an agency sentinel where the licence would be. */
    static final String ROW_OTHER_PERSON =
            "2026-000115,ASSLT,2026/03/04 12:05,\"22 ELM AVE\",2B,SUSP,\"RIVERA, LUIS\",11/02/1975,M,UNK";

    static RawEnvelope envelope(String row, int line) {
        return new RawEnvelope(SOURCE_ID, CONNECTOR, INGEST, INGEST,
                row.getBytes(StandardCharsets.UTF_8),
                SourceOffset.of("incidents.csv#%06d".formatted(line)));
    }

    static List<RawEnvelope> envelopes() {
        return List.of(
                envelope(ROW_BURGLARY_VICTIM, 2),
                envelope(ROW_OTHER_PERSON, 3),
                envelope(ROW_SAME_PERSON_LATER, 4));
    }

    // --- mapping ---------------------------------------------------------

    static MappingDefinition mapping() {
        return new MappingDefinition(
                "cad-to-canonical", "1.0.0", SOURCE_ID,
                DecoderSpec.csv(SOURCE_TYPE, COLUMNS),
                List.of(incidentHop(), personHop(), associationHop()));
    }

    private static HopDefinition incidentHop() {
        return HopDefinition.of(
                "map-incident", "cad-incident-to-canonical", "1.0.0",
                List.of(),
                List.of(
                        TransformSpec.of("incidentNumber", "copy", "INC_NUM"),
                        TransformSpec.of("reportedDateTime", "parseDateTime", "RPT_DTTM",
                                Map.of("pattern", "yyyy/MM/dd HH:mm", "zone", "America/Denver")),
                        TransformSpec.of("locationAddressText", "copy", "ADDR"),
                        TransformSpec.of("callTypeCode", "codeMap", "CALL_TYPE",
                                Map.of("map", "BURG=BURG,THEFT=THEFT,ASSLT=ASSLT,MVA=MVA,DIST=DIST,"
                                        + "SUSP=SUSP,WELCK=WELCK", "default", "OTHER")),
                        TransformSpec.of("beat", "copy", "BEAT")),
                // An incident has an authoritative key in its source; running it through entity
                // resolution would add uncertainty where none exists.
                IdentitySpec.derive("Incident", List.of("incidentNumber"), "INC/RIVERTON-PD/"),
                Map.of());
    }

    private static HopDefinition personHop() {
        return new HopDefinition(
                "map-person", "cad-person-to-canonical", "1.0.0",
                List.of(),
                List.of(
                        // "DOE, JANE M" -> surName DOE, givenName JANE, middleName M.
                        TransformSpec.of("surName", "splitIndex", "NAME_FULL",
                                Map.of("delimiter", ",", "index", "0")),
                        TransformSpec.of("surName", "upper", "surName"),
                        TransformSpec.of("givenNames", "splitIndex", "NAME_FULL",
                                Map.of("delimiter", ",", "index", "1")),
                        TransformSpec.of("givenName", "splitIndex", "givenNames",
                                Map.of("delimiter", " ", "index", "0")),
                        TransformSpec.of("givenName", "upper", "givenName"),
                        TransformSpec.of("middleName", "splitIndex", "givenNames",
                                Map.of("delimiter", " ", "index", "1")),
                        TransformSpec.of("birthDate", "parseDate", "DOB",
                                Map.of("pattern", "MM/dd/yyyy")),
                        TransformSpec.of("sexCode", "codeMap", "SEX",
                                Map.of("map", "M=M,F=F,X=X,U=U", "default", "U")),
                        // UNK is the agency's sentinel for "not recorded"; carrying it into
                        // canonical would make every consumer learn one agency's vocabulary.
                        TransformSpec.of("driverLicenseId", "nullIf", "DL_NUM",
                                Map.of("values", "UNK,N/A,NONE")),
                        TransformSpec.of("driverLicenseId", "regexReplace", "driverLicenseId",
                                Map.of("pattern", "[^A-Za-z0-9]", "replacement", ""))),
                IdentitySpec.resolve("Person", "bundled-deterministic", Map.of(
                        "surName", "surName",
                        "givenName", "givenName",
                        "birthDate", "birthDate",
                        "driverLicenseId", "driverLicenseId")),
                Map.of(),
                // "JANE M" on the way to givenName and middleName; not part of canonical Person.
                List.of("givenNames"));
    }

    private static HopDefinition associationHop() {
        return HopDefinition.of(
                "map-person-incident", "cad-association-to-canonical", "1.0.0",
                List.of("map-person", "map-incident"),
                List.of(TransformSpec.of("involvementCode", "codeMap", "ROLE",
                        Map.of("map", "VICT=VICTIM,SUSP=SUSPECT,WITN=WITNESS,RP=REPORTING_PARTY",
                                "default", "OTHER"))),
                // A function of the two entities it links, so the same pair always yields the same
                // association and a replay does not double every edge in the graph.
                IdentitySpec.derive("PersonIncidentAssociation",
                        List.of("map-incident.canonicalId", "map-person.canonicalId"), "PIA/"),
                Map.of("person", "map-person", "incident", "map-incident"));
    }

    // --- contracts -------------------------------------------------------

    /**
     * The columns every hop expects to be there, with no value-level constraint.
     *
     * <p>Every hop declares the whole row, so a source adding or removing a column is caught
     * wherever it lands. Value-level expectations -- patterns, required -- belong to the hop that
     * actually consumes the field, which is what stops a malformed date of birth from taking the
     * incident down with it.
     */
    private static List<FieldExpectation> allColumns() {
        List<FieldExpectation> fields = new ArrayList<>();
        CadMappingFixture.COLUMNS.forEach(column ->
                fields.add(FieldExpectation.optional(column, FieldType.STRING)));
        return fields;
    }

    /** Replaces one column's expectation, leaving the rest of the row shape declared. */
    private static List<FieldExpectation> withExpectations(FieldExpectation... constrained) {
        Map<String, FieldExpectation> byName = new LinkedHashMap<>();
        allColumns().forEach(field -> byName.put(field.name(), field));
        for (FieldExpectation expectation : constrained) {
            byName.put(expectation.name(), expectation);
        }
        return List.copyOf(byName.values());
    }

    /** What the incident hop needs: an incident number and a reported time it can parse. */
    static Schema incidentInputSchema() {
        return Schema.strict(SOURCE_TYPE, "1.0.0", withExpectations(
                FieldExpectation.required("INC_NUM", FieldType.STRING).withPattern("^\\d{4}-\\d{6}$"),
                FieldExpectation.required("CALL_TYPE", FieldType.STRING),
                FieldExpectation.required("RPT_DTTM", FieldType.STRING)
                        .withPattern("^\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}$")));
    }

    /** What the person hop needs: a name, and a date of birth in the declared format. */
    static Schema personInputSchema() {
        return Schema.strict(SOURCE_TYPE, "1.0.0", withExpectations(
                FieldExpectation.required("NAME_FULL", FieldType.STRING),
                FieldExpectation.optional("DOB", FieldType.STRING)
                        .withPattern("^\\d{2}/\\d{2}/\\d{4}$")));
    }

    /** What the association hop needs: a role code and the two entities it links. */
    static Schema associationInputSchema() {
        return Schema.strict(SOURCE_TYPE, "1.0.0", withExpectations(
                FieldExpectation.required("ROLE", FieldType.STRING)));
    }

    static Map<String, HopContract> contracts() {
        Map<String, HopContract> contracts = new LinkedHashMap<>();
        contracts.put("map-incident", contract(
                "cad-incident-to-canonical", "map-incident", "Incident", incidentInputSchema()));
        contracts.put("map-person", contract(
                "cad-person-to-canonical", "map-person", "Person", personInputSchema()));
        contracts.put("map-person-incident", contract(
                "cad-association-to-canonical", "map-person-incident", "PersonIncidentAssociation",
                associationInputSchema()));
        return contracts;
    }

    private static HopContract contract(String name, String hopId, String canonicalType, Schema expects) {
        CanonicalTypeDescriptor descriptor = CoreCanonicalTypes.byName(canonicalType).orElseThrow();
        // Derived from the model rather than restated, so a canonical schema cannot drift from it.
        Schema emits = Schema.ofCanonical(descriptor);
        return new SchemaHopContract(ContractId.of(name, "1.0.0"), hopId, expects, emits);
    }

    static Map<String, CanonicalTypeDescriptor> canonicalTypes() {
        Map<String, CanonicalTypeDescriptor> types = new LinkedHashMap<>();
        CoreCanonicalTypes.ALL.forEach(type -> types.put(type.name(), type));
        return types;
    }

    // --- identity --------------------------------------------------------

    /**
     * A deterministic test double for the resolution SPI.
     *
     * <p>Spec §8 requires the provider interface to be defined and exercised by a test double in
     * Phase 1, which is what this is. It applies the tier rules ADR 0014 pins for the bundled
     * resolver -- licence exact, then normalised name plus date of birth -- so the mapping is
     * exercised against real resolution behaviour rather than a stub that returns a constant.
     */
    static final class TestDoubleResolver implements ResolutionProvider {

        private final Map<ResolutionKey, ClusterId> index = new LinkedHashMap<>();

        @Override
        public ResolutionResult resolve(EntityAttributes attributes) {
            if (!"Person".equals(attributes.entityType())) {
                throw new UnsupportedEntityTypeException("test-double", attributes.entityType());
            }

            List<ResolutionKey> keys = keysFor(attributes);
            for (ResolutionKey key : keys) {
                ClusterId existing = index.get(key);
                if (existing != null) {
                    // Link every key this record offers, so a later record matching on any of them
                    // reaches the same cluster.
                    keys.forEach(other -> index.putIfAbsent(other, existing));
                    return ResolutionResult.matched(existing, confidenceFor(key.tier()),
                            List.of(MatchEvidence.matched("TIER_" + key.tier(),
                                    List.of(key.tier()), key, "matched an indexed key")));
                }
            }

            if (keys.isEmpty()) {
                ClusterId isolated = ClusterId.seededBy("Person",
                        ResolutionKey.of("SOURCE", attributes.sourceRecordKey()));
                return ResolutionResult.created(isolated,
                        List.of(MatchEvidence.newCluster("no identity-bearing attribute was present")));
            }

            ClusterId created = ClusterId.seededBy("Person", keys.getFirst());
            keys.forEach(key -> index.put(key, created));
            return ResolutionResult.created(created,
                    List.of(MatchEvidence.newCluster("no existing cluster matched")));
        }

        private static List<ResolutionKey> keysFor(EntityAttributes attributes) {
            List<ResolutionKey> keys = new ArrayList<>(2);
            attributes.attribute("driverLicenseId")
                    .map(licence -> licence.replaceAll("[^A-Za-z0-9]", "").toUpperCase(java.util.Locale.ROOT))
                    .filter(licence -> !licence.isEmpty())
                    .ifPresent(licence -> keys.add(ResolutionKey.of("DL", licence)));

            attributes.attribute("birthDate").ifPresent(birthDate ->
                    attributes.attribute("surName").ifPresent(surName -> keys.add(ResolutionKey.of(
                            "NAME_DOB",
                            (surName + "|" + attributes.attribute("givenName").orElse("")
                                    + "|" + birthDate).toUpperCase(java.util.Locale.ROOT)))));
            return keys;
        }

        private static double confidenceFor(String tier) {
            return "DL".equals(tier) ? 0.99 : 0.90;
        }

        @Override
        public ProviderCapabilities capabilities() {
            return new ProviderCapabilities("test-double", java.util.Set.of("Person"),
                    false, false, java.util.Set.of("surName"), List.of(0.99, 0.90, 1.0));
        }
    }
}
