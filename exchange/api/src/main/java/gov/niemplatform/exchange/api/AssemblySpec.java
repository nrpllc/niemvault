package gov.niemplatform.exchange.api;

import gov.niemplatform.canonical.meta.CanonicalKind;
import gov.niemplatform.canonical.meta.CanonicalRoleDescriptor;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * How canonical records become one document, declared rather than coded (ADR 0034).
 *
 * <p>A repository submission is a nested document -- an arrest carrying its subject, its charges,
 * and each charge's disposition and sentence. The obvious way to build one is a method that walks
 * those relationships in Java. That is what the first CCH writer did, and it is why adding a charge
 * to a submission meant editing a class and cutting a platform release.
 *
 * <p>So the walk is configuration. An exchange names a root type and the associations to follow
 * from it, and each of those may follow further:
 *
 * <pre>{@code
 * assemble:
 *   root: Arrest
 *   follow:
 *     - association: ArrestSubjectAssociation
 *       role: person
 *       as: subject
 *     - association: ArrestChargeAssociation
 *       role: charge
 *       as: charges
 *       follow:
 *         - association: ChargeDispositionAssociation
 *           role: disposition
 *           as: disposition
 * }</pre>
 *
 * <h2>Why associations rather than a join tree</h2>
 *
 * <p>The relationships are already in the canonical model, declared with roles and targets and
 * carrying their own NIEM provenance. An exchange that restated them as parent/child blocks with
 * join keys would be a second description of the same structure, and two descriptions of one
 * structure are two things that can disagree -- with the disagreement showing up as a charge
 * attached to the wrong arrest.
 *
 * <p>It also means an exchange cannot invent a relationship the model does not have. If a
 * submission needs an edge that is not in the canonical model, that is a modelling gap to be fixed
 * in the model, where it is versioned and NIEM-attributed, rather than papered over in one
 * exchange's configuration.
 *
 * <h2>Validated on load, against the model</h2>
 *
 * <p>Every step is checked before anything runs (ADR 0010): that the association exists, that it
 * is an association and not an entity, that it declares the named role, that the role's target is
 * reachable, and that following it does not loop. A misspelled role would otherwise surface as an
 * empty element in a submitted document -- which a repository accepts, because an absent optional
 * element is not an error, and nobody finds out until someone asks why no charge ever has a
 * disposition.
 */
public record AssemblySpec(String rootType, List<Follow> follow) {

    public AssemblySpec {
        Objects.requireNonNull(rootType, "rootType");
        follow = List.copyOf(follow);
    }

    /**
     * One hop from the type in hand to another, across a declared association.
     *
     * @param association the canonical association type to traverse
     * @param role which role on that association names the record being pulled in; the other role
     *     is the one already in hand
     * @param as the name this element takes in the assembled document, which is the only part of
     *     the walk that is the wire format's business rather than the model's
     * @param follow further hops from the record just pulled in
     */
    public record Follow(String association, String role, String as, List<Follow> follow) {

        public Follow {
            Objects.requireNonNull(association, "association");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(as, "as");
            follow = List.copyOf(follow);
        }
    }

    /** One thing wrong with an assembly, named where an author can find it. */
    public record Problem(String at, String detail) {

        @Override
        public String toString() {
            return "  " + at + ": " + detail;
        }
    }

    /**
     * Checks this assembly against the canonical model.
     *
     * @param model every canonical type this deployment carries
     * @return every problem found, so an author fixes them in one pass rather than one per attempt
     */
    public List<Problem> validate(List<CanonicalTypeDescriptor> model) {
        List<Problem> problems = new ArrayList<>();
        Optional<CanonicalTypeDescriptor> root = byName(model, rootType);
        if (root.isEmpty()) {
            problems.add(new Problem("assemble.root",
                    "'" + rootType + "' is not a canonical type in this deployment; it carries "
                            + model.stream().map(CanonicalTypeDescriptor::name).sorted().toList()));
            return problems;
        }
        if (root.get().kind() != CanonicalKind.ENTITY) {
            problems.add(new Problem("assemble.root",
                    "'" + rootType + "' is an association. A document is rooted on an entity and "
                            + "reaches its associations by following them"));
            return problems;
        }
        // Seeded with the root so a follow that leads straight back to it is caught as a loop
        // rather than assembled into a document that contains itself.
        Set<String> visited = new LinkedHashSet<>();
        visited.add(rootType);
        validateFollows(model, rootType, follow, "assemble.follow", visited, problems);
        return problems;
    }

    private void validateFollows(
            List<CanonicalTypeDescriptor> model,
            String fromType,
            List<Follow> follows,
            String at,
            Set<String> visited,
            List<Problem> problems) {

        Set<String> namesUsed = new LinkedHashSet<>();
        for (int index = 0; index < follows.size(); index++) {
            Follow step = follows.get(index);
            String where = at + "[" + index + "]";

            if (!namesUsed.add(step.as())) {
                problems.add(new Problem(where,
                        "'as: " + step.as() + "' is used twice at this level; two elements of one "
                                + "name would silently overwrite each other in the document"));
            }

            Optional<CanonicalTypeDescriptor> association = byName(model, step.association());
            if (association.isEmpty()) {
                problems.add(new Problem(where,
                        "'" + step.association() + "' is not a canonical type in this deployment"));
                continue;
            }
            if (association.get().kind() != CanonicalKind.ASSOCIATION) {
                problems.add(new Problem(where,
                        "'" + step.association() + "' is an entity, not an association; only an "
                                + "association can be followed"));
                continue;
            }

            Optional<CanonicalRoleDescriptor> target = association.get().role(step.role());
            if (target.isEmpty()) {
                problems.add(new Problem(where,
                        "'" + step.association() + "' has no role '" + step.role() + "'; its roles "
                                + "are " + association.get().roles().stream()
                                .map(CanonicalRoleDescriptor::name).toList()));
                continue;
            }

            // The association has to actually touch what is in hand, or the walk does not connect.
            // This is the check that catches following ChargeDispositionAssociation directly from
            // an Arrest -- which reads plausibly and would assemble nothing at all.
            boolean anchored = association.get().roles().stream()
                    .anyMatch(role -> !role.name().equals(step.role())
                            && role.targetType().equals(fromType));
            if (!anchored) {
                problems.add(new Problem(where,
                        "'" + step.association() + "' does not connect to '" + fromType + "'. Its "
                                + "other role points at " + association.get().roles().stream()
                                .filter(role -> !role.name().equals(step.role()))
                                .map(CanonicalRoleDescriptor::targetType).toList()
                                + ", so following it from here would assemble nothing"));
                continue;
            }

            String reached = target.get().targetType();
            if (!visited.add(reached)) {
                problems.add(new Problem(where,
                        "following '" + step.association() + "' reaches '" + reached + "', which is "
                                + "already in this branch of the document; a document that contains "
                                + "itself does not terminate"));
                continue;
            }
            validateFollows(model, reached, step.follow(), where + ".follow", visited, problems);
            visited.remove(reached);
        }
    }

    /** Every canonical type this assembly touches, root first, for reporting and for gating. */
    public List<String> typesTouched(List<CanonicalTypeDescriptor> model) {
        Set<String> touched = new LinkedHashSet<>();
        touched.add(rootType);
        collectTypes(model, follow, touched);
        return List.copyOf(touched);
    }

    private void collectTypes(
            List<CanonicalTypeDescriptor> model, List<Follow> follows, Set<String> touched) {
        for (Follow step : follows) {
            touched.add(step.association());
            byName(model, step.association())
                    .flatMap(association -> association.role(step.role()))
                    .ifPresent(role -> touched.add(role.targetType()));
            collectTypes(model, step.follow(), touched);
        }
    }

    private static Optional<CanonicalTypeDescriptor> byName(
            List<CanonicalTypeDescriptor> model, String name) {
        return model.stream().filter(type -> type.name().equals(name)).findFirst();
    }
}
