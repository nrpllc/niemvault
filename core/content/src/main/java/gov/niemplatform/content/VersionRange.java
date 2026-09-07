package gov.niemplatform.content;

import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

/**
 * The span of platform versions a piece of content declares it works with (spec §7).
 *
 * <p>Half-open: the minimum is inclusive and the maximum exclusive. That is the convention that
 * lets a module say "any 0.x from 0.3 onwards" as {@code 0.3.0} to {@code 1.0.0} without having to
 * name a highest patch that does not exist yet.
 *
 * <p>Spec §7 puts this in commercial terms: an agency on an older platform release must be able to
 * take newer domain mappings, and the change board needs that conversation to be tractable. A
 * declared range is what makes "will this run on what we have?" answerable without a meeting.
 *
 * @param minimum lowest platform version this content supports, inclusive
 * @param maximum first platform version this content does <em>not</em> support, or {@code null}
 *     for no upper bound
 */
public record VersionRange(SemanticVersion minimum, SemanticVersion maximum) implements Serializable {

    public VersionRange {
        Objects.requireNonNull(minimum, "minimum");
        if (maximum != null && maximum.compareTo(minimum) <= 0) {
            throw new IllegalArgumentException(
                    "A version range must be non-empty: maximum " + maximum
                            + " is not above minimum " + minimum);
        }
    }

    /** Everything from a version onwards, with no upper bound. */
    public static VersionRange from(String minimum) {
        return new VersionRange(SemanticVersion.parse(minimum), null);
    }

    /** A half-open span: minimum inclusive, maximum exclusive. */
    public static VersionRange between(String minimum, String maximum) {
        return new VersionRange(SemanticVersion.parse(minimum), SemanticVersion.parse(maximum));
    }

    public boolean includes(SemanticVersion version) {
        if (version.isBelow(minimum)) {
            return false;
        }
        return maximum == null || version.isBelow(maximum);
    }

    public Optional<SemanticVersion> upperBound() {
        return Optional.ofNullable(maximum);
    }

    /**
     * Why a version falls outside, in words an operator can act on.
     *
     * <p>"Incompatible" on its own tells someone nothing about whether to upgrade the platform or
     * roll back the content, which is the only decision they are trying to make.
     */
    public String explain(SemanticVersion version) {
        if (includes(version)) {
            return "platform " + version + " is within " + this;
        }
        if (version.isBelow(minimum)) {
            return "platform " + version + " is older than the minimum this content supports ("
                    + minimum + "); upgrade the platform or use an earlier version of the content";
        }
        return "platform " + version + " is at or beyond " + maximum
                + ", which this content declares it does not support; use a newer version of the "
                + "content or roll the platform back";
    }

    @Override
    public String toString() {
        return maximum == null ? ">= " + minimum : ">= " + minimum + " and < " + maximum;
    }
}
