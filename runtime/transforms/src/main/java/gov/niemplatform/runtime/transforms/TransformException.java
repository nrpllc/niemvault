package gov.niemplatform.runtime.transforms;

import java.util.Objects;

/**
 * A transform received a value it could not convert.
 *
 * <p>Spec §9: structured, never a bare string. Structure matters more here than elsewhere,
 * because the pipeline converts this straight into a contract failure -- and a failure that
 * cannot say which field, which transform, and what shape of value arrived is a failure nobody
 * can act on.
 *
 * <p>The offending value is carried as a redacted shape, never literally. See ADR 0015.
 */
public class TransformException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String transformType;
    private final String target;
    private final String sourceField;
    private final String expectation;
    private final String actualShape;

    public TransformException(
            String transformType, String target, String sourceField, String expectation, String actualShape) {
        super("%s could not produce '%s' from '%s': expected %s, found %s"
                .formatted(transformType, target, sourceField, expectation, actualShape));
        this.transformType = Objects.requireNonNull(transformType, "transformType");
        this.target = Objects.requireNonNull(target, "target");
        this.sourceField = sourceField;
        this.expectation = expectation;
        this.actualShape = actualShape;
    }

    public String transformType() {
        return transformType;
    }

    public String target() {
        return target;
    }

    public String sourceField() {
        return sourceField;
    }

    /** What the transform required, in words, for the contract failure it becomes. */
    public String expectation() {
        return expectation;
    }

    /** Redacted description of what arrived. */
    public String actualShape() {
        return actualShape;
    }
}
