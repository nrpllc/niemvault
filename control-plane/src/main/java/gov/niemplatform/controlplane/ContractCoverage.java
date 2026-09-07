package gov.niemplatform.controlplane;

import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.contracts.FieldExpectation;
import gov.niemplatform.contracts.HopContract;
import gov.niemplatform.contracts.Schema;
import gov.niemplatform.contracts.SchemaHopContract;
import gov.niemplatform.runtime.engine.HopDefinition;
import gov.niemplatform.runtime.engine.MappingDefinition;
import gov.niemplatform.runtime.transforms.TransformSpec;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Whether a mapping actually satisfies the contracts that gate it.
 *
 * <h2>The failure this exists to prevent</h2>
 *
 * <p>A contract's emit side requires a field. No step in the hop writes it. Everything loads: the
 * mapping is valid YAML naming a contract that exists at the version it pins, and every check the
 * platform had before this one passes. Then it is deployed, and the contract gate rejects
 * <em>every single record</em>, and the quarantine fills up with the entire feed.
 *
 * <p>Nothing about that is discovered at deploy time by accident. It is knowable from the two
 * artifacts sitting next to each other on disk, and it is knowable while the person who introduced
 * it still has the mapping open. Spec §4.2 is explicit that bad data must not halt the pipeline —
 * which is exactly why a mapping that quarantines everything fails quietly rather than loudly, and
 * why it has to be caught here.
 *
 * <p>This is a check on <em>content coherence</em>, not on either artifact alone, which is why it
 * lives beside the authoring surface rather than inside the mapping loader. A mapping is not wrong
 * on its own for failing to produce a field; it is wrong in combination with the contract that
 * demands one.
 */
public final class ContractCoverage {

    /** What kind of hole was found. */
    public enum Kind {
        /** The contract requires the field; no step produces it. Every record would quarantine. */
        REQUIRED_NOT_PRODUCED,
        /** A step writes a field the emitted type does not declare. */
        PRODUCED_NOT_DECLARED,
        /** A step reads a source column the contract's inbound schema does not declare. */
        READ_NOT_EXPECTED
    }

    /** One hole, phrased for whoever has to fix it. */
    public record Gap(String hopId, String field, Kind kind, String message) {}

    private ContractCoverage() {}

    /**
     * Cross-references every hop against its contract.
     *
     * @param contractsByHop contracts keyed by the hop they gate; a hop with no contract is skipped,
     *     because "the contract is missing" is a different problem, already reported elsewhere
     */
    public static List<Gap> check(MappingDefinition mapping, Map<String, HopContract> contractsByHop) {
        Objects.requireNonNull(mapping, "mapping");
        List<Gap> gaps = new ArrayList<>();
        for (HopDefinition hop : mapping.hops()) {
            HopContract contract = contractsByHop.get(hop.hopId());
            if (contract instanceof SchemaHopContract schema) {
                checkHop(mapping, hop, schema, gaps);
            }
        }
        return List.copyOf(gaps);
    }

    private static void checkHop(MappingDefinition mapping, HopDefinition hop,
            SchemaHopContract contract, List<Gap> gaps) {

        Set<String> written = new LinkedHashSet<>();
        Set<String> read = new LinkedHashSet<>();
        for (TransformSpec step : hop.steps()) {
            written.add(step.target());
            read.addAll(step.from());
        }
        Set<String> scratch = Set.copyOf(hop.scratch());

        // --- the emit side: what the record must carry to get through the gate ---
        Schema emits = contract.emits();
        for (FieldExpectation expected : emits.fields()) {
            if (!expected.required() || written.contains(expected.name())) {
                continue;
            }
            if (isSuppliedByThePlatform(expected.name(), hop)) {
                continue;
            }
            gaps.add(new Gap(hop.hopId(), expected.name(), Kind.REQUIRED_NOT_PRODUCED,
                    "hop '%s' emits %s, which requires '%s', but no step writes it — "
                            .formatted(hop.hopId(), emits.id(), expected.name())
                            + "every record would be quarantined"));
        }

        for (String target : written) {
            if (scratch.contains(target) || emits.field(target).isPresent()) {
                continue;
            }
            gaps.add(new Gap(hop.hopId(), target, Kind.PRODUCED_NOT_DECLARED,
                    "hop '%s' writes '%s', which %s does not declare — "
                            .formatted(hop.hopId(), target, emits.id())
                            + (emits.allowUnexpectedFields()
                                    ? "it will be carried but never stored; declare it scratch if that is intended"
                                    : "the record would be quarantined; declare it scratch if it is working state")));
        }

        // --- the expect side: what the hop is allowed to read ---
        Schema expects = contract.expects();
        Set<String> columns = Set.copyOf(mapping.decoder().columns());
        for (String input : read) {
            // Only source columns are governed by the inbound schema. A step reading what an
            // earlier step wrote is internal to the hop and no business of the contract's.
            if (!columns.contains(input) || written.contains(input)) {
                continue;
            }
            if (expects.field(input).isEmpty() && !expects.allowUnexpectedFields()) {
                gaps.add(new Gap(hop.hopId(), input, Kind.READ_NOT_EXPECTED,
                        "hop '%s' reads column '%s', which %s does not declare — "
                                .formatted(hop.hopId(), input, expects.id())
                                + "the inbound gate would reject the record before the step runs"));
            }
        }
    }

    /**
     * Fields the platform fills in rather than the mapping.
     *
     * <p>The canonical id is assigned by identity resolution after the steps run, and a role is
     * filled from the hop this one depends on. Reporting either as unproduced would be crying wolf
     * on every association hop in existence, and a check that is wrong on correct content gets
     * switched off.
     */
    private static boolean isSuppliedByThePlatform(String field, HopDefinition hop) {
        return field.equals(CanonicalTypeDescriptor.CANONICAL_ID_FIELD)
                || hop.roles().containsKey(field);
    }
}
