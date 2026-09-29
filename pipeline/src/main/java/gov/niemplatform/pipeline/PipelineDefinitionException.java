package gov.niemplatform.pipeline;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A pipeline could not be read, or does not describe one this deployment can run.
 *
 * <p>Structured and never a bare string (spec §9), every problem at once.
 */
public class PipelineDefinitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Path file;
    private final transient List<String> problems;

    public PipelineDefinitionException(Path file, List<String> problems) {
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
                file == null ? "This pipeline" : file.toString(),
                problems.size(), problems.size() == 1 ? "" : "s"));
        problems.forEach(problem -> lines.add("  " + problem));
        return String.join(System.lineSeparator(), lines);
    }
}
