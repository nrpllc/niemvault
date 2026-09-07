package gov.niemplatform.controlplane;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Surgical edits to a contract's YAML text.
 *
 * <p>Same discipline as {@link MappingText}, for the same reason: a contract explains itself. The
 * law enforcement person contract carries a note saying the date pattern is what turns a source
 * quietly switching from {@code MM/dd/yyyy} to ISO into a quarantined record rather than into
 * silently missing dates of birth across the whole agency. Parsing that into records and writing it
 * back out erases the sentence and keeps the regex, which is exactly backwards.
 *
 * <h2>What is different from a mapping</h2>
 *
 * <p>Fields here are addressed by <em>name</em>, not position. A schema may not declare the same
 * field twice — {@code Schema} enforces it — so a name is unambiguous, and an author thinking about
 * a contract is thinking about {@code DOB}, not about the fourth entry in a list.
 *
 * <p>This one also inserts keys that are absent, which {@link MappingText} refuses to do. Marking a
 * field required when it carries no {@code required:} line is the single most common contract edit
 * there is, and the place to put the line is not a guess: field entries are flat maps of scalars,
 * so it goes after {@code type:}.
 */
public final class ContractText {

    /** {@code expects:} or {@code emits:}, the two sides a contract has. */
    public enum Side {
        EXPECTS("expects"),
        EMITS("emits");

        private final String key;

        Side(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    /** {@code - name: DOB}, the start of a field entry. */
    private static final Pattern FIELD_START = Pattern.compile("^(\\s*)-\\s+name:\\s*(\\S+)\\s*$");

    /** A scalar key on its own line, possibly on the {@code - } marker line. */
    private static final String SCALAR_KEY = "^(\\s*)(?:-\\s+)?%s:\\s*(.*)$";

    private static final Pattern PLAIN_SCALAR = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.\\-/]*");

    /** Keys a field entry may carry, in the order they are written when inserted. */
    private static final List<String> FIELD_KEYS =
            List.of("name", "type", "required", "repeated", "refType", "codeList", "pattern");

    private final List<String> lines;

    public ContractText(String yaml) {
        this.lines = new ArrayList<>(Objects.requireNonNull(yaml, "yaml").lines().toList());
    }

    private ContractText(List<String> lines) {
        this.lines = lines;
    }

    public String text() {
        return String.join("\n", lines) + "\n";
    }

    /** Whether this contract governs the given hop. */
    public boolean declaresHop(String hopId) {
        return lines.stream()
                .filter(line -> indentOf(line) == 0)
                .anyMatch(line -> line.strip().equals("hop: " + hopId));
    }

    /** The fields one side declares, in the order the file declares them. */
    public List<String> fieldNames(Side side) {
        return fieldBlocks(side).stream().map(Block::name).toList();
    }

    // --- editing ----------------------------------------------------------

    /**
     * Sets one attribute of one field, inserting the line if the field does not carry it yet.
     *
     * <p>Removing rather than writing {@code false} where that is the default: a contract littered
     * with {@code required: false} is harder to read than one that says nothing, and the two mean
     * the same thing to the loader.
     */
    public ContractText setFieldAttribute(Side side, String fieldName, String key, String value) {
        if (key.equals("name")) {
            throw new IllegalArgumentException(
                    "renaming a field would silently detach it from the mapping steps that read it; "
                            + "add the new field and remove the old one");
        }
        Block field = field(side, fieldName);
        Pattern pattern = Pattern.compile(SCALAR_KEY.formatted(Pattern.quote(key)));

        for (int line = field.start(); line < field.end(); line++) {
            Matcher matcher = pattern.matcher(lines.get(line));
            if (!matcher.matches() || indentOf(lines.get(line)) > field.childIndent()) {
                continue;
            }
            if (isDefault(key, value)) {
                List<String> edited = new ArrayList<>(lines);
                edited.remove(line);
                return new ContractText(edited);
            }
            List<String> edited = new ArrayList<>(lines);
            edited.set(line, lines.get(line).substring(0, matcher.start(2)) + scalar(value));
            return new ContractText(edited);
        }

        if (isDefault(key, value)) {
            return this;
        }
        return new ContractText(insert(field, key, value));
    }

    /** Adds a field to a side, at the end of its list. */
    public ContractText addField(Side side, String name, String type) {
        if (fieldNames(side).contains(name)) {
            throw new IllegalArgumentException(
                    "'" + name + "' is already declared; a schema may not name a field twice");
        }
        List<Block> fields = fieldBlocks(side);
        int indent;
        int at;
        if (fields.isEmpty()) {
            int key = fieldsKeyLine(side);
            indent = indentOf(lines.get(key)) + 2;
            at = key + 1;
        } else {
            Block last = fields.getLast();
            indent = last.markerIndent();
            at = last.end();
        }

        String pad = " ".repeat(indent);
        List<String> edited = new ArrayList<>(lines);
        edited.addAll(at, List.of(pad + "- name: " + scalar(name), pad + "  type: " + scalar(type)));
        return new ContractText(edited);
    }

    /**
     * Removes a field.
     *
     * <p>Nothing here checks whether a mapping still reads it. That check belongs to
     * {@link ContractCoverage}, which sees both artifacts; refusing here would mean this class had
     * to be handed the mapping too, and would still be the wrong place to say so.
     */
    public ContractText removeField(Side side, String fieldName) {
        Block field = field(side, fieldName);
        List<String> edited = new ArrayList<>(lines);
        edited.subList(field.start(), field.end()).clear();
        if (field.start() < edited.size() && edited.get(field.start()).isBlank()
                && field.start() > 0 && edited.get(field.start() - 1).isBlank()) {
            edited.remove(field.start());
        }
        return new ContractText(edited);
    }

    // --- locating ---------------------------------------------------------

    private record Block(String name, int start, int end, int markerIndent, int childIndent) {}

    private Block field(Side side, String fieldName) {
        return fieldBlocks(side).stream()
                .filter(block -> block.name().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "'%s' declares no field '%s'".formatted(side.key(), fieldName)));
    }

    private int sideLine(Side side) {
        for (int line = 0; line < lines.size(); line++) {
            if (lines.get(line).strip().equals(side.key() + ":") && indentOf(lines.get(line)) == 0) {
                return line;
            }
        }
        throw new IllegalArgumentException("this contract declares no '" + side.key() + "'");
    }

    private int sideEnd(int sideLine) {
        for (int line = sideLine + 1; line < lines.size(); line++) {
            String text = lines.get(line);
            if (!text.isBlank() && !isComment(text) && indentOf(text) == 0) {
                return line;
            }
        }
        return lines.size();
    }

    private int fieldsKeyLine(Side side) {
        int start = sideLine(side);
        int end = sideEnd(start);
        for (int line = start + 1; line < end; line++) {
            if (lines.get(line).strip().equals("fields:")) {
                return line;
            }
        }
        throw new IllegalArgumentException(
                "'%s' declares no 'fields'; it derives its schema from the canonical model"
                        .formatted(side.key()));
    }

    private List<Block> fieldBlocks(Side side) {
        int fieldsKey;
        try {
            fieldsKey = fieldsKeyLine(side);
        } catch (IllegalArgumentException derivedFromTheModel) {
            // An emits side written as `canonicalType: Person` has no field list to edit. Its
            // fields come from the canonical model, and that is where they should be changed.
            return List.of();
        }
        int fieldsIndent = indentOf(lines.get(fieldsKey));
        int end = sideEnd(sideLine(side));

        List<Integer> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int markerIndent = -1;

        for (int line = fieldsKey + 1; line < end; line++) {
            String text = lines.get(line);
            if (text.isBlank() || isComment(text)) {
                continue;
            }
            int indent = indentOf(text);
            if (indent <= fieldsIndent) {
                end = line;
                break;
            }
            Matcher matcher = FIELD_START.matcher(text);
            if (matcher.matches() && (markerIndent < 0 || indent == markerIndent)) {
                markerIndent = indent;
                starts.add(line);
                names.add(matcher.group(2));
            }
        }

        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            // A comment sitting directly above a field documents that field and travels with it.
            int start = starts.get(i);
            while (start > fieldsKey + 1 && isComment(lines.get(start - 1))
                    && indentOf(lines.get(start - 1)) == markerIndent
                    && (i == 0 || start - 1 > starts.get(i - 1))) {
                start--;
            }
            int stop = i + 1 < starts.size() ? starts.get(i + 1) : end;
            blocks.add(new Block(names.get(i), start, trimTrailingBlanks(starts.get(i), stop),
                    markerIndent, markerIndent + 2));
        }
        return blocks;
    }

    /** Inserts a key into a field entry, in the order the schema declares its keys. */
    private List<String> insert(Block field, String key, String value) {
        int rank = FIELD_KEYS.indexOf(key);
        int at = field.end();
        for (int line = field.start(); line < field.end(); line++) {
            String text = lines.get(line);
            if (indentOf(text) > field.childIndent() || isComment(text) || text.isBlank()) {
                continue;
            }
            String existing = text.strip().replaceFirst("^-\\s+", "").replaceFirst(":.*$", "");
            int existingRank = FIELD_KEYS.indexOf(existing);
            if (existingRank > rank && rank >= 0) {
                at = line;
                break;
            }
        }
        List<String> edited = new ArrayList<>(lines);
        edited.add(at, " ".repeat(field.childIndent()) + key + ": " + scalar(value));
        return edited;
    }

    private int trimTrailingBlanks(int floor, int end) {
        int trimmed = end;
        while (trimmed > floor + 1 && lines.get(trimmed - 1).isBlank()) {
            trimmed--;
        }
        return trimmed;
    }

    /** Whether writing this value is the same as saying nothing at all. */
    private static boolean isDefault(String key, String value) {
        return (key.equals("required") || key.equals("repeated")) && value.equals("false");
    }

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
     * <p>Patterns get quoted and their backslashes doubled, because a field pattern is a regex and
     * an unquoted one is the fastest way to turn a contract into a file that will not parse.
     */
    private static String scalar(String value) {
        if (value.startsWith("[") || value.startsWith("{")) {
            return value;
        }
        if (PLAIN_SCALAR.matcher(value).matches() || value.equals("true") || value.equals("false")) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
