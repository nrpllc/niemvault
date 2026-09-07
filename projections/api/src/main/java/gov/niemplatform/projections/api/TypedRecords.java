package gov.niemplatform.projections.api;

import gov.niemplatform.canonical.data.Record;
import gov.niemplatform.canonical.meta.CanonicalTypeDescriptor;
import java.util.List;
import java.util.Objects;

/**
 * Records of one canonical type, with the descriptor that gives them meaning.
 *
 * <p>The descriptor travels with the records rather than being looked up by the projection. A
 * projection needs to know which members are association roles and which are ordinary fields to
 * write the right shape, and making it resolve that itself would mean every projection carrying a
 * copy of the model.
 */
public record TypedRecords(CanonicalTypeDescriptor descriptor, List<Record> records) {

    public TypedRecords {
        Objects.requireNonNull(descriptor, "descriptor");
        records = List.copyOf(records);
    }

    public int size() {
        return records.size();
    }
}
