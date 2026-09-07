package gov.niemplatform.runtime.transforms;

import java.io.Serializable;

/**
 * Computes one output field from a record (spec §5).
 *
 * <p>Transforms are <strong>data, not code</strong>. Each one is built from a declarative
 * {@link TransformSpec} that lives in a mapping artifact on disk, because spec §5 requires an
 * agency updating a mapping not to need a platform rebuild. Nothing here may be a lambda written
 * in a mapping-specific class.
 *
 * <p>Pure and deterministic. The same input must always produce the same output -- no clocks, no
 * randomness, no lookups against mutable state. Acceptance criterion 6 (replay reproduces silver
 * exactly) and criterion 7 (batch and streaming produce identical output) both depend on it, and
 * a single impure transform breaks both silently.
 *
 * <p>Serializable because Flink ships the compiled pipeline to its operators.
 */
public interface Transform extends Serializable {

    /** Field this transform writes. */
    String target();

    /** Declarative type name, as it appears in the mapping artifact. */
    String type();

    /**
     * Computes the value for {@link #target()}.
     *
     * <p>Returns {@code null} when the source is genuinely absent -- that is data, not an error,
     * and whether absence is acceptable is the contract's decision, not this transform's.
     *
     * @throws TransformException when the input is present but cannot be transformed, e.g. a date
     *     that does not match its declared pattern. The pipeline turns that into a contract
     *     failure and quarantines the record; it does not halt.
     */
    Object evaluate(TransformInput input);
}
