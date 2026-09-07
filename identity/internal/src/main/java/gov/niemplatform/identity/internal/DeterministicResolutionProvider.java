package gov.niemplatform.identity.internal;

import gov.niemplatform.identity.api.ClusterId;
import gov.niemplatform.identity.api.ClusterIndex;
import gov.niemplatform.identity.api.EntityAttributes;
import gov.niemplatform.identity.api.MatchEvidence;
import gov.niemplatform.identity.api.ProviderCapabilities;
import gov.niemplatform.identity.api.ResolutionKey;
import gov.niemplatform.identity.api.ResolutionProvider;
import gov.niemplatform.identity.api.ResolutionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The bundled default resolver (spec §4.5, ADR 0014).
 *
 * <p>For agencies with no existing entity resolution capability. Same contract as a commercial
 * provider, different engine: deterministic rules in tiers, each reporting a fixed confidence.
 *
 * <table>
 *   <caption>Rule tiers, tried in order</caption>
 *   <tr><th>Tier</th><th>Rule</th><th>Confidence</th></tr>
 *   <tr><td>1</td><td>Driver licence number, exact after normalisation</td><td>0.99</td></tr>
 *   <tr><td>2</td><td>Social Security Number, exact after normalisation</td><td>0.99</td></tr>
 *   <tr><td>3</td><td>Normalised surname, given name, and date of birth</td><td>0.90</td></tr>
 *   <tr><td>—</td><td>Nothing matched: a new cluster</td><td>1.00</td></tr>
 * </table>
 *
 * <h2>What this resolver will not do</h2>
 *
 * <p><strong>It under-matches, deliberately and permanently.</strong> {@code JON SMITH} and
 * {@code JONATHAN SMITH} with the same date of birth stay separate clusters. That is the correct
 * failure direction for criminal justice -- a false merge attributes one person's history to
 * another, which is far worse than a missed link -- but it is a real limitation, not a temporary
 * one, and agencies must be told about it rather than discovering it.
 *
 * <p>When two tiers point at different existing clusters, the stronger tier wins and the weaker
 * key is left pointing where it was. Merging them would be exactly the false merge above.
 *
 * <p>Deterministic: the same records in the same order always produce the same clusters, which is
 * what acceptance criterion 6 needs from it.
 */
public final class DeterministicResolutionProvider implements ResolutionProvider {

    /** Identifier recorded against every assignment this resolver makes. */
    public static final String PROVIDER_ID = "bundled-deterministic";

    /** Attribute names this resolver reads. Anything else is ignored. */
    public static final String ATTR_DRIVER_LICENCE = "driverLicenseId";
    public static final String ATTR_SSN = "socialSecurityId";
    public static final String ATTR_SURNAME = "surName";
    public static final String ATTR_GIVEN_NAME = "givenName";
    public static final String ATTR_BIRTH_DATE = "birthDate";

    static final String TIER_DRIVER_LICENCE = "DL";
    static final String TIER_SSN = "SSN";
    static final String TIER_NAME_DOB = "NAME_DOB";

    private static final double CONFIDENCE_IDENTIFIER = 0.99;
    private static final double CONFIDENCE_NAME_DOB = 0.90;

    private final ClusterIndex index;
    private final Set<String> supportedEntityTypes;

    /** Resolves the Phase 1 scope: {@code Person}. */
    public DeterministicResolutionProvider(ClusterIndex index) {
        this(index, Set.of("Person"));
    }

    /** The agency this resolver assigns identities for, taken from the index it was given. */
    private gov.niemplatform.canonical.meta.TenantId tenant() {
        return index.tenant();
    }

    public DeterministicResolutionProvider(ClusterIndex index, Set<String> supportedEntityTypes) {
        this.index = Objects.requireNonNull(index, "index");
        this.supportedEntityTypes = Set.copyOf(supportedEntityTypes);
    }

    @Override
    public ResolutionResult resolve(EntityAttributes attributes) {
        String entityType = attributes.entityType();
        if (!supportedEntityTypes.contains(entityType)) {
            throw new UnsupportedEntityTypeException(PROVIDER_ID, entityType);
        }

        List<ResolutionKey> keys = keysFor(attributes);

        // Tiers are tried in declaration order, so a licence match beats a name-and-date match
        // even when both would have found a cluster.
        for (ResolutionKey key : keys) {
            Optional<ClusterId> existing = index.find(entityType, key);
            if (existing.isPresent()) {
                ClusterId cluster = existing.get();
                List<ResolutionKey> conflicting = index.link(entityType, cluster, keys);
                return ResolutionResult.matched(cluster, confidenceFor(key.tier()),
                        evidenceForMatch(key, keys, conflicting));
            }
        }

        if (keys.isEmpty()) {
            // Nothing to match on. A cluster of one, seeded from the record itself, so the entity
            // still gets a stable identity rather than being dropped or merged with other
            // attribute-less records.
            ClusterId isolated = ClusterId.seededBy(tenant(), entityType,
                    ResolutionKey.of("SOURCE_RECORD", attributes.sourceRecordKey()));
            index.link(entityType, isolated,
                    List.of(ResolutionKey.of("SOURCE_RECORD", attributes.sourceRecordKey())));
            return ResolutionResult.created(isolated, List.of(MatchEvidence.newCluster(
                    "no identity-bearing attribute was present, so this record cannot be matched")));
        }

        ClusterId created = ClusterId.seededBy(tenant(), entityType, keys.getFirst());
        index.link(entityType, created, keys);
        return ResolutionResult.created(created, List.of(MatchEvidence.newCluster(
                "no existing cluster matched on " + keys.stream().map(ResolutionKey::tier).toList())));
    }

    /**
     * Builds the keys this record offers, strongest tier first.
     *
     * <p>A record contributes every key it can, not just the strongest. That is what lets a later
     * record with only a name and date of birth reach a cluster that was created from a licence.
     */
    private static List<ResolutionKey> keysFor(EntityAttributes attributes) {
        List<ResolutionKey> keys = new ArrayList<>(3);

        attributes.attribute(ATTR_DRIVER_LICENCE)
                .flatMap(Normalisation::identifier)
                .ifPresent(licence -> keys.add(ResolutionKey.of(TIER_DRIVER_LICENCE, licence)));

        attributes.attribute(ATTR_SSN)
                .flatMap(Normalisation::identifier)
                .ifPresent(ssn -> keys.add(ResolutionKey.of(TIER_SSN, ssn)));

        // Surname and date of birth are both required for tier 3. A given name is not: many
        // sources record only an initial, and requiring it would under-match further than the
        // tier already does.
        Optional<String> surname = attributes.attribute(ATTR_SURNAME).flatMap(Normalisation::name);
        Optional<String> birthDate = attributes.attribute(ATTR_BIRTH_DATE).flatMap(Normalisation::date);
        if (surname.isPresent() && birthDate.isPresent()) {
            String givenName = attributes.attribute(ATTR_GIVEN_NAME)
                    .flatMap(Normalisation::name)
                    .orElse("");
            keys.add(ResolutionKey.of(TIER_NAME_DOB,
                    surname.get() + "|" + givenName + "|" + birthDate.get()));
        }
        return keys;
    }

    private static double confidenceFor(String tier) {
        return TIER_NAME_DOB.equals(tier) ? CONFIDENCE_NAME_DOB : CONFIDENCE_IDENTIFIER;
    }

    private static List<MatchEvidence> evidenceForMatch(
            ResolutionKey matched, List<ResolutionKey> offered, List<ResolutionKey> conflicting) {
        List<MatchEvidence> evidence = new ArrayList<>(2);
        evidence.add(MatchEvidence.matched(
                "TIER_" + matched.tier(),
                List.of(fieldsFor(matched.tier())),
                matched,
                "matched an existing cluster on " + matched.tier()));

        if (!conflicting.isEmpty()) {
            // Recorded rather than acted on. Repointing the weaker key would merge two clusters a
            // stronger rule kept apart, and a false merge is the worse error (ADR 0014).
            evidence.add(new MatchEvidence(
                    "TIER_CONFLICT",
                    conflicting.stream().map(key -> fieldsFor(key.tier())).toList(),
                    conflicting,
                    "these keys already point at a different cluster and were left alone; "
                            + "the stronger tier " + matched.tier() + " decided this assignment"));
        }
        return evidence;
    }

    private static String fieldsFor(String tier) {
        return switch (tier) {
            case TIER_DRIVER_LICENCE -> ATTR_DRIVER_LICENCE;
            case TIER_SSN -> ATTR_SSN;
            case TIER_NAME_DOB -> ATTR_SURNAME + "+" + ATTR_GIVEN_NAME + "+" + ATTR_BIRTH_DATE;
            default -> tier;
        };
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(
                PROVIDER_ID,
                supportedEntityTypes,
                false,
                false,
                Set.of(),
                List.of(CONFIDENCE_IDENTIFIER, CONFIDENCE_NAME_DOB, 1.0));
    }
}
