package gov.niemplatform.build.canonical;

import gov.niemplatform.build.canonical.CanonicalDslException.Code;
import gov.niemplatform.build.canonical.CanonicalDslException.Problem;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads canonical type definitions from YAML.
 *
 * <p>Strict by construction: an unrecognised key is an error, not a warning. The DSL is the
 * source of truth for the canonical model (spec §4.1), so a typo that silently drops a NIEM
 * provenance annotation is exactly the class of failure the platform exists to prevent.
 */
public final class CanonicalDslParser {

    private static final Set<String> TYPE_KEYS =
            Set.of("type", "kind", "namespace", "version", "doc", "provenance", "extension", "fields", "roles");
    private static final Set<String> FIELD_KEYS =
            Set.of("name", "type", "required", "repeated", "doc", "provenance", "extension", "codeList", "refType");
    private static final Set<String> ROLE_KEYS = Set.of("name", "target", "doc", "provenance", "extension");
    private static final Set<String> PROVENANCE_KEYS = Set.of("niemNamespace", "niemType", "niemElement");
    private static final Set<String> EXTENSION_KEYS = Set.of("justification");

    private final List<Problem> problems = new ArrayList<>();

    /** Parses every {@code *.yaml} under the given roots into type definitions. */
    public List<TypeDef> parseAll(List<Path> sourceRoots) {
        problems.clear();
        List<TypeDef> types = new ArrayList<>();
        for (Path root : sourceRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            for (Path file : listYaml(root)) {
                TypeDef parsed = parseFile(file);
                if (parsed != null) {
                    types.add(parsed);
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new CanonicalDslException(problems);
        }
        return types;
    }

    private List<Path> listYaml(Path root) {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot enumerate canonical sources under " + root, e);
        }
    }

    private TypeDef parseFile(Path file) {
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object loaded = new Yaml(new SafeConstructor(options)).load(reader);
            if (!(loaded instanceof Map<?, ?> map)) {
                problems.add(new Problem(file, "<root>", Code.MALFORMED_YAML,
                        "expected a mapping at the document root, found "
                                + (loaded == null ? "an empty document" : loaded.getClass().getSimpleName())));
                return null;
            }
            root = castMap(map);
        } catch (IOException e) {
            problems.add(new Problem(file, "<root>", Code.MALFORMED_YAML, "unreadable: " + e.getMessage()));
            return null;
        } catch (RuntimeException e) {
            problems.add(new Problem(file, "<root>", Code.MALFORMED_YAML, String.valueOf(e.getMessage())));
            return null;
        }

        rejectUnknownKeys(file, "<root>", root.keySet(), TYPE_KEYS);

        String name = requireString(file, "<root>", root, "type");
        String namespace = requireString(file, "<root>", root, "namespace");
        String version = requireString(file, "<root>", root, "version");
        String doc = optionalString(root, "doc");
        TypeDef.Kind kind = parseKind(file, root);

        String location = name == null ? "<unnamed type>" : name;
        Provenance provenance = parseProvenance(file, location, root, true);
        Extension extension = parseExtension(file, location, root);

        List<FieldDef> fields = parseFields(file, name, root);
        List<RoleDef> roles = parseRoles(file, name, root);

        if (name == null || namespace == null || version == null || kind == null) {
            return null;
        }
        return new TypeDef(name, kind, namespace, version, doc, provenance, extension, fields, roles, file);
    }

    private TypeDef.Kind parseKind(Path file, Map<String, Object> root) {
        String raw = optionalString(root, "kind");
        if (raw == null) {
            problems.add(new Problem(file, "<root>", Code.MISSING_KEY,
                    "'kind' is required and must be 'entity' or 'association'"));
            return null;
        }
        return switch (raw) {
            case "entity" -> TypeDef.Kind.ENTITY;
            case "association" -> TypeDef.Kind.ASSOCIATION;
            default -> {
                problems.add(new Problem(file, "<root>", Code.INVALID_VALUE,
                        "'kind' must be 'entity' or 'association', found '" + raw + "'"));
                yield null;
            }
        };
    }

    private List<FieldDef> parseFields(Path file, String typeName, Map<String, Object> root) {
        List<FieldDef> fields = new ArrayList<>();
        Object raw = root.get("fields");
        if (raw == null) {
            return fields;
        }
        if (!(raw instanceof List<?> list)) {
            problems.add(new Problem(file, nz(typeName), Code.INVALID_VALUE, "'fields' must be a list"));
            return fields;
        }
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> m)) {
                problems.add(new Problem(file, nz(typeName) + ".fields[" + i + "]", Code.INVALID_VALUE,
                        "each field must be a mapping"));
                continue;
            }
            Map<String, Object> fieldMap = castMap(m);
            String fieldName = optionalString(fieldMap, "name");
            String location = nz(typeName) + "." + (fieldName == null ? "fields[" + i + "]" : fieldName);
            rejectUnknownKeys(file, location, fieldMap.keySet(), FIELD_KEYS);

            if (fieldName == null) {
                problems.add(new Problem(file, location, Code.MISSING_KEY, "'name' is required"));
                continue;
            }
            String fieldType = requireString(file, location, fieldMap, "type");
            if (fieldType == null) {
                continue;
            }
            fields.add(new FieldDef(
                    fieldName,
                    fieldType,
                    booleanValue(file, location, fieldMap, "required", false),
                    booleanValue(file, location, fieldMap, "repeated", false),
                    optionalString(fieldMap, "doc"),
                    parseProvenance(file, location, fieldMap, false),
                    parseExtension(file, location, fieldMap),
                    stringList(file, location, fieldMap, "codeList"),
                    optionalString(fieldMap, "refType")));
        }
        return fields;
    }

    private List<RoleDef> parseRoles(Path file, String typeName, Map<String, Object> root) {
        List<RoleDef> roles = new ArrayList<>();
        Object raw = root.get("roles");
        if (raw == null) {
            return roles;
        }
        if (!(raw instanceof List<?> list)) {
            problems.add(new Problem(file, nz(typeName), Code.INVALID_VALUE, "'roles' must be a list"));
            return roles;
        }
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> m)) {
                problems.add(new Problem(file, nz(typeName) + ".roles[" + i + "]", Code.INVALID_VALUE,
                        "each role must be a mapping"));
                continue;
            }
            Map<String, Object> roleMap = castMap(m);
            String roleName = optionalString(roleMap, "name");
            String location = nz(typeName) + "." + (roleName == null ? "roles[" + i + "]" : roleName);
            rejectUnknownKeys(file, location, roleMap.keySet(), ROLE_KEYS);

            if (roleName == null) {
                problems.add(new Problem(file, location, Code.MISSING_KEY, "'name' is required"));
                continue;
            }
            String target = requireString(file, location, roleMap, "target");
            if (target == null) {
                continue;
            }
            roles.add(new RoleDef(
                    roleName,
                    target,
                    optionalString(roleMap, "doc"),
                    parseProvenance(file, location, roleMap, false),
                    parseExtension(file, location, roleMap)));
        }
        return roles;
    }

    private Provenance parseProvenance(Path file, String location, Map<String, Object> owner, boolean typeLevel) {
        Object raw = owner.get("provenance");
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> m)) {
            problems.add(new Problem(file, location, Code.INVALID_VALUE, "'provenance' must be a mapping"));
            return null;
        }
        Map<String, Object> map = castMap(m);
        rejectUnknownKeys(file, location + ".provenance", map.keySet(), PROVENANCE_KEYS);

        String ns = optionalString(map, "niemNamespace");
        String niemType = optionalString(map, "niemType");
        String niemElement = optionalString(map, "niemElement");

        if (ns == null) {
            problems.add(new Problem(file, location + ".provenance", Code.MISSING_KEY,
                    "'niemNamespace' is required on every provenance declaration"));
        }
        if (typeLevel && niemType == null) {
            problems.add(new Problem(file, location + ".provenance", Code.MISSING_KEY,
                    "type-level provenance requires 'niemType'"));
        }
        if (!typeLevel && niemElement == null && niemType == null) {
            problems.add(new Problem(file, location + ".provenance", Code.MISSING_KEY,
                    "field/role provenance requires 'niemElement' (or 'niemType' for a complex member)"));
        }
        return new Provenance(ns, niemType, niemElement);
    }

    private Extension parseExtension(Path file, String location, Map<String, Object> owner) {
        Object raw = owner.get("extension");
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> m)) {
            problems.add(new Problem(file, location, Code.INVALID_VALUE,
                    "'extension' must be a mapping carrying a 'justification'"));
            return null;
        }
        Map<String, Object> map = castMap(m);
        rejectUnknownKeys(file, location + ".extension", map.keySet(), EXTENSION_KEYS);

        String justification = optionalString(map, "justification");
        if (justification == null || justification.isBlank()) {
            problems.add(new Problem(file, location + ".extension", Code.MISSING_KEY,
                    "every extension requires a written 'justification' (spec section 4.1)"));
            return new Extension("");
        }
        return new Extension(justification);
    }

    // --- primitives -------------------------------------------------------

    private void rejectUnknownKeys(Path file, String location, Set<String> present, Set<String> allowed) {
        Set<String> unknown = new LinkedHashSet<>(present);
        unknown.removeAll(allowed);
        for (String key : unknown) {
            problems.add(new Problem(file, location, Code.UNKNOWN_KEY,
                    "unrecognised key '" + key + "'; allowed keys are " + allowed.stream().sorted().toList()));
        }
    }

    private String requireString(Path file, String location, Map<String, Object> map, String key) {
        String value = optionalString(map, key);
        if (value == null) {
            problems.add(new Problem(file, location, Code.MISSING_KEY, "'" + key + "' is required"));
        }
        return value;
    }

    private static String optionalString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private boolean booleanValue(Path file, String location, Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        problems.add(new Problem(file, location, Code.INVALID_VALUE,
                "'" + key + "' must be a boolean, found '" + value + "'"));
        return fallback;
    }

    private List<String> stringList(Path file, String location, Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            problems.add(new Problem(file, location, Code.INVALID_VALUE, "'" + key + "' must be a list"));
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String nz(String typeName) {
        return typeName == null ? "<unnamed type>" : typeName;
    }
}
