package gov.niemplatform.runtime.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * A mapping artifact could not be loaded.
 *
 * <p>Spec §9 and ADR 0010: validated on load, fails loudly, every problem reported at once. A
 * mapping is the most consequential artifact an agency edits -- it decides what canonical data
 * looks like -- so it gets the same treatment as the canonical DSL and the contract artifacts,
 * down to rejecting unrecognised keys.
 */
public class MappingLoadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** One structured problem: where it is, and what is wrong. */
    public record Problem(String source, String location, Code code, String detail) {

        @Override
        public String toString() {
            return "  [%s] %s (%s): %s".formatted(code, source, location, detail);
        }
    }

    /** Machine-readable problem taxonomy. */
    public enum Code {
        MALFORMED_YAML,
        UNKNOWN_KEY,
        MISSING_KEY,
        INVALID_VALUE,
        UNKNOWN_TRANSFORM,
        UNDECLARED_TARGET,
        GRAPH,
        UNREADABLE
    }

    private final transient List<Problem> problems;

    public MappingLoadException(List<Problem> problems) {
        super(render(problems));
        this.problems = List.copyOf(problems);
    }

    public List<Problem> problems() {
        return problems;
    }

    private static String render(List<Problem> problems) {
        List<String> lines = new ArrayList<>();
        lines.add("Mapping artifact is invalid (%d problem%s):"
                .formatted(problems.size(), problems.size() == 1 ? "" : "s"));
        problems.forEach(problem -> lines.add(problem.toString()));
        return String.join(System.lineSeparator(), lines);
    }
}
