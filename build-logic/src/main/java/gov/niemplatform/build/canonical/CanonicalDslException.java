package gov.niemplatform.build.canonical;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Structured failure from parsing or validating the canonical DSL.
 *
 * <p>Spec §9: no exception carries a bare string as its only payload. Every problem is
 * attributed to a source file and a location within it, and a single throw may report
 * many problems so an author fixes them in one pass rather than one per build.
 */
public class CanonicalDslException extends RuntimeException {

    /** One structured problem: where it is, and what is wrong. */
    public record Problem(Path file, String location, Code code, String detail) {

        @Override
        public String toString() {
            return "  [%s] %s (%s): %s".formatted(code, file.getFileName(), location, detail);
        }
    }

    /** Machine-readable problem taxonomy. */
    public enum Code {
        MALFORMED_YAML,
        UNKNOWN_KEY,
        MISSING_KEY,
        INVALID_VALUE,
        PROVENANCE_REQUIRED,
        PROVENANCE_CONFLICT,
        EXTENSION_NAMESPACE_VIOLATION,
        NIEM_TYPE_REDEFINITION,
        DUPLICATE_DECLARATION,
        UNRESOLVED_REFERENCE,
        UNVERIFIED_NIEM_REFERENCE,
        STRUCTURAL
    }

    private final transient List<Problem> problems;

    public CanonicalDslException(List<Problem> problems) {
        super(render(problems));
        this.problems = List.copyOf(problems);
    }

    public List<Problem> problems() {
        return problems;
    }

    private static String render(List<Problem> problems) {
        List<String> lines = new ArrayList<>();
        lines.add("Canonical model is invalid (%d problem%s):"
                .formatted(problems.size(), problems.size() == 1 ? "" : "s"));
        problems.forEach(p -> lines.add(p.toString()));
        return String.join(System.lineSeparator(), lines);
    }
}
