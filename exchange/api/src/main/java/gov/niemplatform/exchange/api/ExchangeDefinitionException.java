package gov.niemplatform.exchange.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * An exchange definition could not be read, or does not describe an exchange this deployment can
 * perform.
 *
 * <p>Structured and never a bare string (spec §9), with every problem reported at once so an author
 * fixes them in one pass rather than one per attempt.
 */
public class ExchangeDefinitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Path file;
    private final transient List<String> problems;

    public ExchangeDefinitionException(Path file, List<String> problems) {
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
        lines.add("%s %s (%d problem%s):".formatted(
                file == null ? "This exchange definition" : file.toString(),
                file == null ? "is unusable" : "is unusable",
                problems.size(), problems.size() == 1 ? "" : "s"));
        problems.forEach(problem -> lines.add("  " + problem));
        return String.join(System.lineSeparator(), lines);
    }
}
