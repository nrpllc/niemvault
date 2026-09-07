package gov.niemplatform.identity.internal;

import java.util.Locale;
import java.util.Optional;

/**
 * Value normalisation for deterministic matching.
 *
 * <p>Deterministic matching is only as good as its normalisation. {@code K447-1902} and
 * {@code k4471902} are the same licence, and treating them as different is how one human quietly
 * becomes two clusters -- a failure that is invisible until an investigator wonders why a search
 * returns half the incidents it should.
 *
 * <p>Every method here is total and side-effect free: same input, same output, forever. Cluster
 * identifiers are derived from these values, so a change to any of them changes identities that
 * are already in gold. Treat them as a wire format, not as an implementation detail.
 */
final class Normalisation {

    private Normalisation() {}

    /**
     * Government identifiers: letters and digits only, upper-cased.
     *
     * <p>Punctuation in a licence or Social Security Number is presentation, and agencies are
     * inconsistent about it within a single export.
     */
    static Optional<String> identifier(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalised = raw.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return normalised.isEmpty() ? Optional.empty() : Optional.of(normalised);
    }

    /**
     * Names: letters only, upper-cased, internal whitespace collapsed to one space.
     *
     * <p>Hyphens and apostrophes are removed rather than kept, because agencies disagree about
     * them within a single export -- {@code O'BRIEN}, {@code OBRIEN}, and {@code O BRIEN} are one
     * family name written three ways, and a rule that split them would under-match badly.
     */
    static Optional<String> name(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalised = raw.replaceAll("[^A-Za-z\\s]", "")
                .trim()
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
        return normalised.isEmpty() ? Optional.empty() : Optional.of(normalised);
    }

    /**
     * Dates: trimmed, otherwise untouched.
     *
     * <p>Deliberately not reformatted. By the time a value reaches identity resolution it has
     * already been parsed and re-rendered by the mapping, so a date arriving in an unexpected
     * shape here means the mapping is wrong -- and normalising it away would hide that.
     */
    static Optional<String> date(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }
}
