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

    // --- the source's own vocabulary ---------------------------------------

    /**
     * Writes what the agency means by a source column (§4.8, ADR 0019).
     *
     * <p>Handles both forms a column may take, because the whole point is to let someone document a
     * source that was written without documentation. A bare name is rewritten as a name with a doc;
     * an existing doc is replaced.
     *
     * <p>The text is written as a YAML folded block, which is what a paragraph of prose wants to be.
     * A meaning worth capturing is usually a sentence about what the field is <em>not</em> — that a
     * beat is not a postal boundary, that a licence number is sometimes the literal {@code UNK} —
     * and squeezing that onto one quoted line produces something nobody re-reads.
     *
     * @throws IllegalArgumentException if the decoder declares no such column
     */
    public MappingText setColumnDoc(String column, String doc) {
        Block columns = columnsBlock();
        int marker = columnLine(columns, column);
        int indent = indentOf(lines.get(marker));

        // Where the entry ends: the next column, or the end of the list.
        int end = marker + 1;
        while (end < columns.end() && (lines.get(end).isBlank()
                || indentOf(lines.get(end)) > indent)) {
            end++;
        }

        List<String> replacement = new ArrayList<>();
        String pad = " ".repeat(indent);
        replacement.add(pad + "- name: " + scalar(column));
        if (!doc.isBlank()) {
            replacement.add(pad + "  doc: >");
            // Wrapped at a width a person reads comfortably, and indented under the folded scalar.
            for (String line : wrap(doc.strip(), 92 - indent - 4)) {
                replacement.add(pad + "    " + line);
            }
        }

        List<String> edited = new ArrayList<>(lines);
        edited.subList(marker, trimTrailingBlanks(marker, end)).clear();
        edited.addAll(marker, replacement);
        return new MappingText(edited);
    }

    /** The columns a decoder declares, in order, whichever form each is written in. */
    public List<String> columnNames() {
        Block columns = columnsBlock();
        List<String> names = new ArrayList<>();
        for (int line = columns.start(); line < columns.end(); line++) {
            String text = lines.get(line);
            if (text.isBlank() || isComment(text) || indentOf(text) != columns.markerIndent()) {
                continue;
            }
            Matcher bare = BARE_COLUMN.matcher(text);
            if (bare.matches()) {
                names.add(bare.group(2));
                continue;
            }
            Matcher named = NAMED_COLUMN.matcher(text);
            if (named.matches()) {
                names.add(named.group(2));
            }
        }
        return List.copyOf(names);
    }

    /** {@code - INC_NUM} */
    private static final Pattern BARE_COLUMN = Pattern.compile("^(\\s*)-\\s+([^:\\s#][^:#]*?)\\s*$");

    /** {@code - name: INC_NUM} */
    private static final Pattern NAMED_COLUMN =
            Pattern.compile("^(\\s*)-\\s+name:\\s*\"?([^\"\\s]+)\"?\\s*$");

    private Block columnsBlock() {
        for (int line = 0; line < lines.size(); line++) {
            if (!lines.get(line).strip().equals("columns:")) {
                continue;
            }
            int indent = indentOf(lines.get(line));
            int end = lines.size();
            int markerIndent = -1;
            for (int scan = line + 1; scan < lines.size(); scan++) {
                String text = lines.get(scan);
                if (text.isBlank() || isComment(text)) {
                    continue;
                }
                if (indentOf(text) <= indent) {
                    end = scan;
                    break;
                }
                if (markerIndent < 0 && text.stripLeading().startsWith("- ")) {
                    markerIndent = indentOf(text);
                }
            }
            return new Block(line + 1, end, markerIndent < 0 ? indent + 2 : markerIndent, indent);
        }
        throw new IllegalArgumentException("this mapping declares no decoder columns");
    }

    private int columnLine(Block columns, String column) {
        for (int line = columns.start(); line < columns.end(); line++) {
            String text = lines.get(line);
            if (indentOf(text) != columns.markerIndent()) {
                continue;
            }
            Matcher bare = BARE_COLUMN.matcher(text);
            if (bare.matches() && bare.group(2).equals(column)) {
                return line;
            }
            Matcher named = NAMED_COLUMN.matcher(text);
            if (named.matches() && named.group(2).equals(column)) {
                return line;
            }
        }
        throw new IllegalArgumentException("no column '" + column + "' in this mapping");
    }

    /** Breaks prose at word boundaries, so the artifact stays readable in a diff. */
    private static List<String> wrap(String text, int width) {
        List<String> wrapped = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                wrapped.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            wrapped.add(line.toString());
        }
        return wrapped;
    }

    // --- adding, removing, reordering --------------------------------------

    /** Appends a step with no options. */
    public MappingText addStep(String hopId, String target, String type, List<String> from) {
        return addStep(hopId, target, type, from, java.util.Map.of());
    }

    /**
     * Appends a step to the end of a hop, which is where a new one almost always belongs.
     *
     * <p>Options are written with the step rather than in a second edit, so accepting a suggestion
     * that carries a date pattern lands as one change an author can read, not as a step that is
     * briefly wrong followed by a fix.
     */
    public MappingText addStep(String hopId, String target, String type, List<String> from,
            java.util.Map<String, String> options) {
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
        if (!options.isEmpty()) {
            block.add(pad + "  options: { "
                    + options.entrySet().stream()
                            .map(option -> option.getKey() + ": " + scalar(option.getValue()))
                            .reduce((a, b) -> a + ", " + b).orElse("")
                    + " }");
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
