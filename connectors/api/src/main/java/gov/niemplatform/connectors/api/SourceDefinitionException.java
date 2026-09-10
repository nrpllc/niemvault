package gov.niemplatform.connectors.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A source definition could not be read or is not usable.
 *
 * <p>Every problem at once, so an operator fixes them in one pass rather than one per attempt
 * (spec §9).
 */
public class SourceDefinitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Path file;
    private final transient List<String> problems;

    public SourceDefinitionException(Path file, List<String> problems) {
        super(render(file, problems));
        this.file = file;
        this.problems = List.copyOf(problems);
    }

    /** The definition file, where the failure has one. Resolution failures do not. */
    public Path file() {
        return file;
    }

    public List<String> problems() {
        return problems;
    }

    private static String render(Path file, List<String> problems) {
        List<String> lines = new ArrayList<>();
        lines.add(file == null
                ? "Source definition cannot be used (%d problem%s):".formatted(
                        problems.size(), problems.size() == 1 ? "" : "s")
                : "Source definition %s cannot be used (%d problem%s):".formatted(
                        file, problems.size(), problems.size() == 1 ? "" : "s"));
        problems.forEach(problem -> lines.add("  " + problem));
        return String.join(System.lineSeparator(), lines);
    }
}
