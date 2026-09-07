package gov.niemplatform.cli;

import gov.niemplatform.content.SemanticVersion;

/**
 * The platform version this build is (spec §7).
 *
 * <p>Content declares the platform range it supports, and the check is meaningless without knowing
 * what is running. Read from the jar manifest, which the build stamps.
 *
 * <p>Read from a resource the build stamps, which is present however the CLI is launched -- from a
 * jar, from a class directory, and from a test. A jar manifest alone would be absent in the latter
 * two, and a version that vanished there would refuse all content on a developer machine.
 *
 * <p>{@code 0.0.0} only when neither is available, which means content with any real minimum
 * refuses to load. That is the correct answer for a build that cannot say what it is.
 */
final class PlatformVersion {

    private static final String RESOURCE = "/niem-platform.properties";
    private static final SemanticVersion UNKNOWN = SemanticVersion.parse("0.0.0");

    private PlatformVersion() {}

    static SemanticVersion running() {
        return fromResource()
                .or(PlatformVersion::fromJarManifest)
                .orElse(UNKNOWN);
    }

    /** The build stamps this, and it is present however the CLI is launched. */
    private static java.util.Optional<SemanticVersion> fromResource() {
        try (java.io.InputStream stream = PlatformVersion.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return java.util.Optional.empty();
            }
            java.util.Properties properties = new java.util.Properties();
            properties.load(stream);
            return parse(properties.getProperty("platform.version"));
        } catch (java.io.IOException unreadable) {
            return java.util.Optional.empty();
        }
    }

    /** Fallback for a CLI repackaged into someone else's jar without the resource. */
    private static java.util.Optional<SemanticVersion> fromJarManifest() {
        return parse(PlatformVersion.class.getPackage().getImplementationVersion());
    }

    private static java.util.Optional<SemanticVersion> parse(String text) {
        if (text == null || text.isBlank() || text.contains("$")) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(SemanticVersion.parse(text));
        } catch (IllegalArgumentException notSemver) {
            return java.util.Optional.empty();
        }
    }
}
