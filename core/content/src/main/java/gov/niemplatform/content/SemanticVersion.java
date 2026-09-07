package gov.niemplatform.content;

import java.io.Serializable;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A semantic version, used for both platform and content versions (spec §7).
 *
 * <p>The two move independently — the engine versus the definitions — so the platform needs to
 * compare them without conflating them. A version is a number, not a string: comparing
 * {@code "0.10.0"} against {@code "0.9.0"} lexically gives the wrong answer, and an agency told its
 * content was incompatible when it was not would stop trusting the check entirely.
 *
 * <p>Pre-release and build metadata are accepted and ignored for ordering. A deployment running
 * {@code 1.2.0-rc1} should be treated as {@code 1.2.0} for compatibility purposes; refusing to load
 * content on a release candidate would make testing an upgrade impossible.
 */
public record SemanticVersion(int major, int minor, int patch, String qualifier)
        implements Serializable, Comparable<SemanticVersion> {

    private static final Pattern SEMVER =
            Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+](.+))?$");

    public SemanticVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Version components cannot be negative");
        }
    }

    /**
     * Parses a version.
     *
     * @throws IllegalArgumentException if it is not semver, named rather than guessed at
     */
    public static SemanticVersion parse(String text) {
        Objects.requireNonNull(text, "text");
        Matcher matcher = SEMVER.matcher(text.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "'" + text + "' is not a semantic version (MAJOR.MINOR.PATCH)");
        }
        return new SemanticVersion(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                matcher.group(4));
    }

    /** Whether the text is a parseable version, for validation that reports rather than throws. */
    public static boolean isValid(String text) {
        return text != null && SEMVER.matcher(text.trim()).matches();
    }

    @Override
    public int compareTo(SemanticVersion other) {
        // Qualifier deliberately ignored: 1.2.0-rc1 and 1.2.0 are the same version for
        // compatibility, so an agency can test an upgrade with the content it already has.
        int byMajor = Integer.compare(major, other.major);
        if (byMajor != 0) {
            return byMajor;
        }
        int byMinor = Integer.compare(minor, other.minor);
        return byMinor != 0 ? byMinor : Integer.compare(patch, other.patch);
    }

    public boolean isAtLeast(SemanticVersion other) {
        return compareTo(other) >= 0;
    }

    public boolean isBelow(SemanticVersion other) {
        return compareTo(other) < 0;
    }

    /** The version without its qualifier, which is what compatibility is judged on. */
    public String baseVersion() {
        return major + "." + minor + "." + patch;
    }

    @Override
    public String toString() {
        return qualifier == null ? baseVersion() : baseVersion() + "-" + qualifier;
    }
}
