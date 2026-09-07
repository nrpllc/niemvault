package gov.niemplatform.content;

import java.util.ArrayList;
import java.util.List;

/**
 * Content could not be loaded, or the running platform cannot honour what it declares.
 *
 * <p>Spec §9: structured, never a bare string. The message says what to <em>do</em> rather than
 * only that something failed -- "incompatible" alone leaves an operator unable to tell whether to
 * upgrade the platform or roll back the content, which is the only decision they are making.
 */
public class ContentCompatibilityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<String> problems;

    public ContentCompatibilityException(List<String> problems) {
        super(render(problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }

    private static String render(List<String> problems) {
        List<String> lines = new ArrayList<>();
        lines.add("Content cannot be loaded (%d problem%s):"
                .formatted(problems.size(), problems.size() == 1 ? "" : "s"));
        problems.forEach(problem -> lines.add("  " + problem));
        return String.join(System.lineSeparator(), lines);
    }
}
