package gov.niemplatform.controlplane;

import gov.niemplatform.canonical.core.CoreCanonicalTypes;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import java.util.Optional;

/**
 * The canonical types this platform build carries.
 *
 * <p>One place to ask, so the authoring surface and the runtime cannot end up consulting different
 * lists of what a canonical type is.
 */
final class ArtifactTypes {

    private ArtifactTypes() {}

    static Optional<CanonicalTypeDescriptor> byName(String name) {
        return CoreCanonicalTypes.ALL.stream()
                .filter(descriptor -> descriptor.name().equals(name))
                .findFirst();
    }
}
