package gov.niemplatform.controlplane;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Surgical edits to a mapping's YAML text.
 *
 * <h2>Why not edit the model and write it back out</h2>
 *
 * <p>Because a YAML round trip destroys the file. A shipped mapping carries the reasoning behind
 * its steps — why a date pattern is pinned, why a field is scratch, which source quirk a transform
 * exists to absorb — and none of that survives being parsed into records and re-emitted. That
 * commentary is the most expensive thing in the artifact to recreate and the cheapest to lose.
 *
 * <p>So the form edits the <em>text</em>. Changing a step's transform type rewrites the one line
 * that says {@code type:}. Everything else in the file, comments included, is untouched, byte for
 * byte.
 *
 * <h2>What it deliberately will not do</h2>
 *
 * <p>It edits scalars that sit on their own line, and whole step blocks. It does not restructure
 * YAML. An option that does not exist yet, or one whose value spans lines, is left to the text
 * editor rather than guessed at — a form that silently reflows a block map is a form that loses
 * something the next time it meets a file it was not tested against.
 *
 * <p>Every edit returns a new instance. The result is always re-validated through the runtime's
 * loaders before it is offered for saving; nothing here decides that an edit was legal.
 */
public final class MappingText {

    /** {@code - id: map-person}, the start of a hop. */
    private static final Pattern HOP_START = Pattern.compile("^(\\s*)-\\s+id:\\s*(\\S+)\\s*$");

    /** {@code steps:}, the key whose list this class edits. */
    private static final Pattern STEPS_KEY = Pattern.compile("^(\\s*)steps:\\s*$");

    /** The start of any block-sequence item. */
    private static final Pattern ITEM_START = Pattern.compile("^(\\s*)-\\s+\\S.*$");

    /** A scalar key on its own line: {@code type: copy}. */
    private static final String SCALAR_KEY = "^(\\s*)(?:-\\s+)?%s:\\s*(.*)$";

    /** A value inside a flow mapping: {@code options: { delimiter: ",", index: "0" }}. */
    private static final String FLOW_ENTRY = "([{,]\\s*%s\\s*:\\s*)(\"[^\"]*\"|'[^']*'|[^,}]*)";

    /** A scalar that needs no quoting. Anything else is quoted rather than reasoned about. */
    private static final Pattern PLAIN_SCALAR = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.\\-/]*");

    private final List<String> lines;

    public MappingText(String yaml) {
        this.lines = new ArrayList<>(Objects.requireNonNull(yaml, "yaml").lines().toList());
    }

    private MappingText(List<String> lines) {
        this.lines = lines;
    }

    /** The document, with the trailing newline a text file is expected to end on. */
    public String text() {
        return String.join("\n", lines) + "\n";
    }

    /** How many steps a hop has, so a caller can address the last one. */
    public int stepCount(String hopId) {
        return stepBlocks(hopId).size();
    }

    // --- editing a step ---------------------------------------------------

    /**
     * Rewrites one scalar of one step: {@code target} or {@code type}.
     *
     * @throws IllegalArgumentException if the step does not declare that key, because inventing a
     *     line means choosing where to put it, and the file already has an opinion about that
     */
    public MappingText setStepField(String hopId, int stepIndex, String key, String value) {
        Block step = step(hopId, stepIndex);
        Pattern pattern = Pattern.compile(SCALAR_KEY.formatted(Pattern.quote(key)));

        for (int line = step.start(); line < step.end(); line++) {
            Matcher matcher = pattern.matcher(lines.get(line));
            if (!matcher.matches() || indentOf(lines.get(line)) > step.childIndent()) {
                continue;
            }
            List<String> edited = new ArrayList<>(lines);
            // The prefix is kept verbatim so a key written on the "- " marker line stays there.
            edited.set(line, lines.get(line).substring(0, matcher.start(2)) + scalar(value));
            return new MappingText(edited);
        }
        throw new IllegalArgumentException(
                "step %d of hop '%s' does not declare '%s'".formatted(stepIndex, hopId, key));
    }

    /** Rewrites a step's inputs, which is what moves an edge on the graph. */
    public MappingText setStepFrom(String hopId, int stepIndex, List<String> from) {
        return setStepField(hopId, stepIndex, "from",
                from.stream().map(MappingText::scalar).reduce((a, b) -> a + ", " + b)
                        .map(joined -> "[" + joined + "]")
                        .orElse("[]"));
    }

    /**
     * Rewrites an option a step already declares, in either flow or block style.
     *
     * @throws IllegalArgumentException if the option is absent or spans lines
     */
    public MappingText setStepOption(String hopId, int stepIndex, String key, String value) {
        Block step = step(hopId, stepIndex);
        Pattern flow = Pattern.compile(FLOW_ENTRY.formatted(Pattern.quote(key)));
        Pattern block = Pattern.compile(SCALAR_KEY.formatted(Pattern.quote(key)));

        boolean insideOptions = false;
        int optionsIndent = -1;
        for (int line = step.start(); line < step.end(); line++) {
            String text = lines.get(line);

            // Flow style: options: { delimiter: ",", index: "0" }
            if (text.stripLeading().startsWith("options:")) {
                Matcher inline = flow.matcher(text);
                if (inline.find()) {
                    List<String> edited = new ArrayList<>(lines);
                    edited.set(line, text.substring(0, inline.start(2)) + scalar(value)
                            + text.substring(inline.end(2)));
                    return new MappingText(edited);
                }
                insideOptions = text.strip().equals("options:");
                optionsIndent = indentOf(text);
                continue;
            }

            // Block style: the option is a line of its own, indented under options:.
            if (insideOptions && indentOf(text) > optionsIndent) {
                Matcher matcher = block.matcher(text);
                if (matcher.matches()) {
                    if (matcher.group(2).isBlank()) {
                        // A key with nothing after it opens a nested structure. Rewriting the
                        // scalar would silently orphan whatever is underneath.
                        break;
                    }
                    List<String> edited = new ArrayList<>(lines);
                    edited.set(line, text.substring(0, matcher.start(2)) + scalar(value));
                    return new MappingText(edited);
                }
            } else if (insideOptions && !text.isBlank()) {
                insideOptions = false;
            }
        }
        throw new IllegalArgumentException(
                "step %d of hop '%s' has no single-line option '%s'; edit it as text"
                        .formatted(stepIndex, hopId, key));
    }

    // --- adding, removing, reordering --------------------------------------

    /** Appends a step to the end of a hop, which is where a new one almost always belongs. */
    public MappingText addStep(String hopId, String target, String type, List<String> from) {
        List<Block> steps = stepBlocks(hopId);
        int indent;
        int at;
        if (steps.isEmpty()) {
            Block hop = hop(hopId);
            int stepsKey = stepsKeyLine(hop);
            indent = indentOf(lines.get(stepsKey)) + 2;
            at = stepsKey + 1;
        } else {
            Block last = steps.getLast();
            indent = last.markerIndent();
            at = last.end();
        }

        String pad = " ".repeat(indent);
        List<String> block = new ArrayList<>();
        block.add(pad + "- target: " + scalar(target));
        block.add(pad + "  type: " + scalar(type));
        if (!from.isEmpty()) {
            block.add(pad + "  from: ["
                    + from.stream().map(MappingText::scalar).reduce((a, b) -> a + ", " + b).orElse("")
                    + "]");
        }

        List<String> edited = new ArrayList<>(lines);
        edited.addAll(at, block);
        return new MappingText(edited);
    }

    /** Removes a step, and the blank line it leaves behind. */
    public MappingText removeStep(String hopId, int stepIndex) {
        Block step = step(hopId, stepIndex);
        if (stepBlocks(hopId).size() == 1) {
            // A hop with no steps produces nothing. The loader would reject it, but the message
            // an author needs is about the step they just deleted, not about a malformed hop.
            throw new IllegalStateException(
                    "hop '%s' has only one step; a hop with no steps emits nothing".formatted(hopId));
        }

        List<String> edited = new ArrayList<>(lines);
        edited.subList(step.start(), step.end()).clear();
        // Collapse the double blank the removal leaves between its neighbours.
        if (step.start() < edited.size() && edited.get(step.start()).isBlank()
                && step.start() > 0 && edited.get(step.start() - 1).isBlank()) {
            edited.remove(step.start());
        }
        return new MappingText(edited);
    }

    /**
     * Moves a step earlier or later.
     *
     * <p>Order is meaning, not presentation: steps in this DSL read each other's targets, so a
     * step moved above the one that produces its input stops working.
     */
    public MappingText moveStep(String hopId, int stepIndex, int delta) {
        List<Block> steps = stepBlocks(hopId);
        int destination = stepIndex + delta;
        if (destination < 0 || destination >= steps.size()) {
            throw new IllegalArgumentException(
                    "step %d of hop '%s' cannot move to position %d"
                            .formatted(stepIndex, hopId, destination));
        }

        Block moving = steps.get(stepIndex);
        List<String> block = new ArrayList<>(lines.subList(moving.start(), moving.end()));
        List<String> edited = new ArrayList<>(lines);
        edited.subList(moving.start(), moving.end()).clear();

        Block anchor = steps.get(destination);
        int at = destination < stepIndex
                ? anchor.start()
                : anchor.end() - block.size();
        edited.addAll(at, block);
        return new MappingText(edited);
    }

    // --- locating ---------------------------------------------------------

    /**
     * A run of lines forming one block.
     *
     * @param markerIndent the column the {@code -} sits in
     * @param childIndent the column this item's own keys sit in
     */
    private record Block(int start, int end, int markerIndent, int childIndent) {}

    private Block hop(String hopId) {
        return hopBlocks().stream()
                .filter(entry -> entry.getKey().equals(hopId))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no hop '" + hopId + "'"));
    }

    private Block step(String hopId, int stepIndex) {
        List<Block> steps = stepBlocks(hopId);
        if (stepIndex < 0 || stepIndex >= steps.size()) {
            throw new IllegalArgumentException(
                    "hop '%s' has %d steps; no step %d".formatted(hopId, steps.size(), stepIndex));
        }
        return steps.get(stepIndex);
    }

    private List<java.util.Map.Entry<String, Block>> hopBlocks() {
        int hopsKey = -1;
        for (int line = 0; line < lines.size(); line++) {
            if (lines.get(line).strip().equals("hops:") && indentOf(lines.get(line)) == 0) {
                hopsKey = line;
                break;
            }
        }
        if (hopsKey < 0) {
            return List.of();
        }

        List<Integer> starts = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        int markerIndent = -1;
        int end = lines.size();

        for (int line = hopsKey + 1; line < lines.size(); line++) {
            String text = lines.get(line);
            if (text.isBlank() || isComment(text)) {
                continue;
            }
            int indent = indentOf(text);
            if (markerIndent >= 0 && indent < markerIndent) {
                end = line;
                break;
            }
            Matcher matcher = HOP_START.matcher(text);
            if (matcher.matches() && (markerIndent < 0 || indent == markerIndent)) {
                markerIndent = indent;
                starts.add(line);
                ids.add(matcher.group(2));
            }
        }

        List<java.util.Map.Entry<String, Block>> blocks = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            int stop = i + 1 < starts.size() ? starts.get(i + 1) : end;
            blocks.add(java.util.Map.entry(ids.get(i),
                    new Block(start, trimTrailingBlanks(start, stop), markerIndent, markerIndent + 2)));
        }
        return blocks;
    }

    private int stepsKeyLine(Block hop) {
        for (int line = hop.start(); line < hop.end(); line++) {
            if (STEPS_KEY.matcher(lines.get(line)).matches()) {
                return line;
            }
        }
        throw new IllegalArgumentException("hop at line " + (hop.start() + 1) + " declares no steps");
    }

    private List<Block> stepBlocks(String hopId) {
        Block hop = hop(hopId);
        int stepsKey = stepsKeyLine(hop);
        int stepsIndent = indentOf(lines.get(stepsKey));

        List<Integer> starts = new ArrayList<>();
        int markerIndent = -1;
        int end = hop.end();

        for (int line = stepsKey + 1; line < hop.end(); line++) {
            String text = lines.get(line);
            if (text.isBlank() || isComment(text)) {
                continue;
            }
            int indent = indentOf(text);
            if (indent <= stepsIndent) {
                end = line;
                break;
            }
            Matcher matcher = ITEM_START.matcher(text);
            if (matcher.matches() && (markerIndent < 0 || indent == markerIndent)) {
                markerIndent = indent;
                starts.add(line);
            }
        }

        List<Block> steps = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            // A comment sitting directly above a step documents that step, so it travels with it.
            // Leaving it behind on a delete would strand an explanation over the wrong lines.
            int start = starts.get(i);
            while (start > stepsKey + 1 && isComment(lines.get(start - 1))
                    && indentOf(lines.get(start - 1)) == markerIndent
                    && (i == 0 || start - 1 >= starts.get(i - 1))) {
                start--;
            }
            int stop = i + 1 < starts.size() ? starts.get(i + 1) : end;
            steps.add(new Block(start, trimTrailingBlanks(starts.get(i), stop),
                    markerIndent, markerIndent + 2));
        }
        return steps;
    }

    private int trimTrailingBlanks(int floor, int end) {
        int trimmed = end;
        while (trimmed > floor + 1 && lines.get(trimmed - 1).isBlank()) {
            trimmed--;
        }
        return trimmed;
    }

    // --- scalars ----------------------------------------------------------

    private static boolean isComment(String line) {
        return line.stripLeading().startsWith("#");
    }

    private static int indentOf(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        return indent;
    }

    /**
     * Renders a value for YAML.
     *
     * <p>Quotes anything that is not plainly a bare scalar rather than working out whether YAML
     * would survive it. A needlessly quoted string is harmless; an unquoted {@code 0} that the
     * loader reads as a number where a string was meant is a bug that reaches production.
     */
    private static String scalar(String value) {
        if (value.startsWith("[") || value.startsWith("{")) {
            return value;
        }
        if (PLAIN_SCALAR.matcher(value).matches()) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
