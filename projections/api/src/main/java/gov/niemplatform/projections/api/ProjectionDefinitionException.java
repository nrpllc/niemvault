package gov.niemplatform.projections.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A projection definition could not be read, or names a projection this deployment cannot open.
 *
 * <p>Structured and never a bare string (spec §9), with every problem reported at once so an author
 * fixes them in one pass rather than one per attempt.
 */
public class ProjectionDefinitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Path file;
    private final transient List<String> problems;

    public ProjectionDefinitionException(Path file, List<String> problems) {
        super(render(file, problems));
        this.file = file;
        this.problems = List.copyOf(problems);
    }

    public Path file() {
        return file;
    }

    public List<String> problems() {
        return problems;
    }

    private static String render(Path file, List<String> problems) {
        List<String> lines = new ArrayList<>();
        lines.add("%s is unusable (%d problem%s):".formatted(
                file == null ? "This projection definition" : file.toString(),
                problems.size(), problems.size() == 1 ? "" : "s"));
        problems.forEach(problem -> lines.add("  " + problem));
        return String.join(System.lineSeparator(), lines);
    }
}
