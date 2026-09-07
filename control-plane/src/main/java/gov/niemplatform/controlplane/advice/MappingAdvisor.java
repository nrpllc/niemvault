package gov.niemplatform.controlplane.advice;

import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import gov.niemplatform.observability.ValueShape;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Something that proposes how a source column should become a canonical field (ADR 0023).
 *
 * <h2>An advisor proposes; it never writes</h2>
 *
 * <p>Every suggestion is applied by a person, through the same edit path a hand-made change takes,
 * and validated by the same loaders. There is deliberately no method here that changes anything. A
 * mapping is a reviewed artifact (§4.8) and a machine cannot be the reviewer.
 *
 * <h2>An advisor never sees a record value</h2>
 *
 * <p>{@link Context} is the enforcement point, not a convention. It carries column names, canonical
 * field names and their NIEM types, and {@link ValueShape} — the redacted shape the platform already
 * computes for observability, {@code ###-##-####} rather than the number. There is no field on it
 * that can hold a value, so no implementation can leak one, including one talking to a remote
 * endpoint an agency has configured.
 *
 * <p>That is not a limitation in practice. Knowing a column is always eight digits and two slashes
 * is what picks {@code parseDate} and its pattern. Knowing whose date of birth it is adds nothing.
 */
public interface MappingAdvisor {

    /** A short name for the advisor, shown next to its suggestions so authors know what proposed. */
    String id();

    /**
     * Everything an advisor is allowed to know.
     *
     * @param sourceColumns the columns the source declares, in declaration order
     * @param shapes redacted shapes observed per column, where any have been observed. Empty is
     *     normal — an author may be writing a mapping before a single file has landed.
     * @param target the canonical type this hop emits
     * @param alreadyMapped canonical field names some step already writes; an advisor proposes for
     *     what is left rather than re-proposing work already done
     * @param transformTypes the transform vocabulary the runtime actually has, so an advisor cannot
     *     propose one that does not exist
     */
    record Context(
            List<String> sourceColumns,
            Map<String, ValueShape> shapes,
            CanonicalTypeDescriptor target,
            List<String> alreadyMapped,
            List<String> transformTypes) {

        public Context {
            sourceColumns = List.copyOf(sourceColumns);
            shapes = Map.copyOf(shapes);
            Objects.requireNonNull(target, "target");
            alreadyMapped = List.copyOf(alreadyMapped);
            transformTypes = List.copyOf(transformTypes);
        }
    }

    /**
     * One proposed step.
     *
     * @param confidence 0..1. Shown to the author rather than used as a threshold: a low-confidence
     *     suggestion for a field nothing else covers is still the most useful thing on screen.
     * @param rationale why this was proposed, in words an author can disagree with. A suggestion
     *     that cannot explain itself has no business entering an artifact that will be audited.
     */
    record Suggestion(
            String target,
            String transformType,
            List<String> from,
            Map<String, String> options,
            double confidence,
            String rationale) {

        public Suggestion {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(transformType, "transformType");
            from = List.copyOf(from);
            options = Map.copyOf(options);
            Objects.requireNonNull(rationale, "rationale");
            if (confidence < 0 || confidence > 1) {
                throw new IllegalArgumentException("confidence must be 0..1, was " + confidence);
            }
        }
    }

    /**
     * Proposes steps for fields the mapping does not yet produce.
     *
     * <p>Returns rather than throws when it has nothing to say. An advisor with no ideas is the
     * normal case for an unfamiliar source, not an error.
     */
    List<Suggestion> suggest(Context context);
}
