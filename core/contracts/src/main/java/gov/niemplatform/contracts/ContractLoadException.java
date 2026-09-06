package gov.niemplatform.contracts;

import java.util.ArrayList;
import java.util.List;

/**
 * A contract artifact could not be loaded.
 *
 * <p>Spec §9: config is schema-validated on load, fails fast and loudly, and no exception carries
 * a bare string as its only payload. Every problem is attributed to a source and a location, and
 * one throw reports them all -- an operator iterating one error per deployment against a
 * slow-starting pipeline will start guessing.
 */
public class ContractLoadException extends RuntimeException {

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
        UNRESOLVED_CANONICAL_TYPE,
        DUPLICATE_CONTRACT,
        UNREADABLE
    }

    private final transient List<Problem> problems;

    public ContractLoadException(List<Problem> problems) {
        super(render(problems));
        this.problems = List.copyOf(problems);
    }

    public List<Problem> problems() {
        return problems;
    }

    private static String render(List<Problem> problems) {
        List<String> lines = new ArrayList<>();
        lines.add("Contract artifacts are invalid (%d problem%s):"
                .formatted(problems.size(), problems.size() == 1 ? "" : "s"));
        problems.forEach(problem -> lines.add(problem.toString()));
        return String.join(System.lineSeparator(), lines);
    }
}
