package gov.niemplatform.build.canonical;

import gov.niemplatform.niem.NiemRelease;

import gov.niemplatform.build.canonical.CanonicalDslException.Code;
import gov.niemplatform.build.canonical.CanonicalDslException.Problem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Enforces the canonical-model rules of spec §4.1 across a parsed set of type definitions.
 *
 * <p>Three rules carry most of the weight and are the reason this runs at build time rather
 * than as a review checklist:
 *
 * <ul>
 *   <li>Every type, field, and role declares NIEM provenance <em>or</em> declares itself an
 *       extension with a written justification -- never neither, never both.
 *   <li>Extensions live in a namespace separate from NIEM-sourced types, in both directions.
 *   <li>No two canonical types claim the same NIEM type. Silent redefinition of a NIEM type
 *       is the failure this check exists to make impossible.
 * </ul>
 */
public final class CanonicalModelValidator {

    /** Scalar types the DSL understands. Anything else is a modelling error. */
    public static final Set<String> SCALAR_TYPES =
            Set.of("string", "code", "date", "dateTime", "integer", "decimal", "boolean");

    /** Reserved because the platform, not the mapping author, assigns it. See spec §4.5. */
    public static final Set<String> RESERVED_FIELD_NAMES = Set.of("canonicalId", "roles");

    private static final Pattern TYPE_NAME = Pattern.compile("^[A-Z][A-Za-z0-9]*$");
    private static final Pattern MEMBER_NAME = Pattern.compile("^[a-z][A-Za-z0-9]*$");
    private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");

    private final String extensionNamespaceRoot;
    private final NiemRelease release;
    private final List<Problem> problems = new ArrayList<>();

    /**
     * Structural validation only, without resolving NIEM citations.
     *
     * <p>For callers testing the DSL's own rules. Whether the model <em>must</em> be verified against
     * a release is a build-level decision, enforced by the codegen task, which refuses to run
     * without one -- not something to infer here from an absent argument.
     */
    public CanonicalModelValidator(String extensionNamespaceRoot) {
        this(extensionNamespaceRoot, NiemRelease.none());
    }

    /**
     * @param release the NIEM release every provenance claim is resolved against. An empty release
     *     means nothing can be verified, which the validator reports rather than passing over --
     *     silently accepting unverifiable citations is the failure ADR 0011 exists to prevent.
     */
    public CanonicalModelValidator(String extensionNamespaceRoot, NiemRelease release) {
        this.extensionNamespaceRoot = extensionNamespaceRoot;
        this.release = release == null ? NiemRelease.none() : release;
    }

    /** Validates the model, throwing {@link CanonicalDslException} with every problem found. */
    public void validate(List<TypeDef> types) {
        problems.clear();

        Map<String, TypeDef> byName = new HashMap<>();
        Map<String, TypeDef> byNiemType = new HashMap<>();

        for (TypeDef type : types) {
            checkTypeIdentity(type, byName);
            checkProvenanceExclusivity(type, byNiemType);
            checkNamespaceSegregation(type);
            checkStructure(type);
        }

        // Reference resolution needs the full type set, so it runs as a second pass.
        for (TypeDef type : types) {
            checkReferences(type, byName);
            if (!release.isEmpty()) {
                checkNiemReferences(type);
            }
        }

        if (!problems.isEmpty()) {
            throw new CanonicalDslException(problems);
        }
    }

    private void checkTypeIdentity(TypeDef type, Map<String, TypeDef> byName) {
        Path file = type.sourceFile();
        if (!TYPE_NAME.matcher(type.name()).matches()) {
            problems.add(new Problem(file, type.name(), Code.INVALID_VALUE,
                    "type name must be PascalCase"));
        }
        if (!SEMVER.matcher(type.version()).matches()) {
            problems.add(new Problem(file, type.name(), Code.INVALID_VALUE,
                    "'version' must be semver (MAJOR.MINOR.PATCH), found '" + type.version() + "'"));
        }
        if (!type.namespace().startsWith("http://") && !type.namespace().startsWith("https://")) {
            problems.add(new Problem(file, type.name(), Code.INVALID_VALUE,
                    "'namespace' must be an absolute http(s) URI, found '" + type.namespace() + "'"));
        }
        TypeDef previous = byName.putIfAbsent(type.name(), type);
        if (previous != null) {
            problems.add(new Problem(file, type.name(), Code.DUPLICATE_DECLARATION,
                    "type '" + type.name() + "' is already declared in "
                            + previous.sourceFile().getFileName()));
        }
    }

    private void checkProvenanceExclusivity(TypeDef type, Map<String, TypeDef> byNiemType) {
        Path file = type.sourceFile();
        requireExactlyOne(file, type.name(), "type", type.provenance() != null, type.extension() != null);

        if (type.provenance() != null && type.provenance().niemType() != null) {
            String key = type.provenance().niemNamespace() + "#" + type.provenance().niemType();
            TypeDef previous = byNiemType.putIfAbsent(key, type);
            if (previous != null) {
                problems.add(new Problem(file, type.name(), Code.NIEM_TYPE_REDEFINITION,
                        "NIEM type '" + type.provenance().niemType() + "' is already mapped by canonical type '"
                                + previous.name() + "'; a NIEM type must not be redefined by a second canonical type"));
            }
        }

        for (FieldDef field : type.fields()) {
            requireExactlyOne(file, type.name() + "." + field.name(), "field",
                    field.provenance() != null, field.extension() != null);
        }
        for (RoleDef role : type.roles()) {
            requireExactlyOne(file, type.name() + "." + role.name(), "role",
                    role.provenance() != null, role.extension() != null);
        }
    }

    private void requireExactlyOne(Path file, String location, String what, boolean hasProvenance, boolean hasExtension) {
        if (hasProvenance && hasExtension) {
            problems.add(new Problem(file, location, Code.PROVENANCE_CONFLICT,
                    "a " + what + " declares both 'provenance' and 'extension'; it is one or the other"));
        } else if (!hasProvenance && !hasExtension) {
            problems.add(new Problem(file, location, Code.PROVENANCE_REQUIRED,
                    "every " + what + " must declare NIEM 'provenance' or an 'extension' with a justification"));
        }
    }

    /**
     * Resolves every NIEM citation against the release on disk.
     *
     * <p>The check ADR 0011 refused to sign off Phase 1 without. A provenance nobody has verified is
     * worse than none at all, because it reads as a citation: it survives review, it gets copied
     * into the next model, and it is discovered by an integrator whose data will not exchange.
     */
    private void checkNiemReferences(TypeDef type) {
        Path file = type.sourceFile();
        if (type.provenance() != null) {
            verify(file, type.name(), type.provenance(), true);
        }
        for (FieldDef field : type.fields()) {
            if (field.provenance() != null) {
                verify(file, type.name() + "." + field.name(), field.provenance(), false);
            }
        }
        for (RoleDef role : type.roles()) {
            if (role.provenance() != null) {
                verify(file, type.name() + "." + role.name(), role.provenance(), false);
            }
        }
    }

    private void verify(Path file, String location, Provenance provenance, boolean typeLevel) {
        var namespace = release.namespace(provenance.niemNamespace());
        if (namespace.isEmpty()) {
            problems.add(new Problem(file, location, Code.UNVERIFIED_NIEM_REFERENCE,
                    "namespace '" + provenance.niemNamespace() + "' is not in the NIEM release; "
                            + "the release carries " + release.namespaces().stream()
                            .map(NiemRelease.Namespace::uri).toList()));
            return;
        }

        // A type-level citation may name a type; a field may name an element, or a type when the
        // member is complex. Both forms are checked against what the namespace actually declares.
        String reference = provenance.reference();
        String localName = NiemRelease.localName(reference);
        boolean asType = typeLevel || provenance.niemElement() == null;

        boolean declared = asType
                ? namespace.get().declaresType(localName)
                : namespace.get().declaresElement(localName);
        if (declared) {
            return;
        }

        // Say where it does exist. "Not found" alone leaves an author to search thousands of names
        // by hand, and the commonest mistake by far is citing the right name in the wrong namespace.
        List<String> elsewhere = release.whereDeclared(localName, asType);
        problems.add(new Problem(file, location, Code.UNVERIFIED_NIEM_REFERENCE,
                "'" + reference + "' is not declared by " + provenance.niemNamespace()
                        + (elsewhere.isEmpty()
                                ? "; no namespace in the release declares '" + localName + "'"
                                : "; it is declared as " + elsewhere)));
    }

    private void checkNamespaceSegregation(TypeDef type) {
        Path file = type.sourceFile();
        boolean inExtensionNamespace = type.namespace().startsWith(extensionNamespaceRoot);

        if (type.isExtension() && !inExtensionNamespace) {
            problems.add(new Problem(file, type.name(), Code.EXTENSION_NAMESPACE_VIOLATION,
                    "extension type must live under the extension namespace root '" + extensionNamespaceRoot
                            + "', found '" + type.namespace() + "'"));
        }
        if (!type.isExtension() && inExtensionNamespace) {
            problems.add(new Problem(file, type.name(), Code.EXTENSION_NAMESPACE_VIOLATION,
                    "NIEM-sourced type must not live under the extension namespace root '"
                            + extensionNamespaceRoot + "'"));
        }
    }

    private void checkStructure(TypeDef type) {
        Path file = type.sourceFile();

        if (type.kind() == TypeDef.Kind.ENTITY) {
            if (!type.roles().isEmpty()) {
                problems.add(new Problem(file, type.name(), Code.STRUCTURAL,
                        "an entity must not declare 'roles'; use kind 'association'"));
            }
            if (type.fields().isEmpty()) {
                problems.add(new Problem(file, type.name(), Code.STRUCTURAL,
                        "an entity must declare at least one field"));
            }
        } else {
            if (type.roles().size() < 2) {
                problems.add(new Problem(file, type.name(), Code.STRUCTURAL,
                        "an association must declare at least two roles, found " + type.roles().size()));
            }
        }

        Set<String> seenFields = new HashSet<>();
        for (FieldDef field : type.fields()) {
            String location = type.name() + "." + field.name();
            if (!MEMBER_NAME.matcher(field.name()).matches()) {
                problems.add(new Problem(file, location, Code.INVALID_VALUE, "field name must be camelCase"));
            }
            if (RESERVED_FIELD_NAMES.contains(field.name())) {
                problems.add(new Problem(file, location, Code.INVALID_VALUE,
                        "'" + field.name() + "' is reserved; the platform assigns it, not the mapping"));
            }
            if (!seenFields.add(field.name())) {
                problems.add(new Problem(file, location, Code.DUPLICATE_DECLARATION, "duplicate field name"));
            }
            checkFieldType(file, location, field);
        }

        Set<String> seenRoles = new HashSet<>();
        for (RoleDef role : type.roles()) {
            String location = type.name() + "." + role.name();
            if (!MEMBER_NAME.matcher(role.name()).matches()) {
                problems.add(new Problem(file, location, Code.INVALID_VALUE, "role name must be camelCase"));
            }
            if (!seenRoles.add(role.name())) {
                problems.add(new Problem(file, location, Code.DUPLICATE_DECLARATION, "duplicate role name"));
            }
        }
    }

    private void checkFieldType(Path file, String location, FieldDef field) {
        boolean isRef = "ref".equals(field.type());
        if (!isRef && !SCALAR_TYPES.contains(field.type())) {
            problems.add(new Problem(file, location, Code.INVALID_VALUE,
                    "unknown field type '" + field.type() + "'; expected one of "
                            + SCALAR_TYPES.stream().sorted().toList() + " or 'ref'"));
        }
        if (isRef && field.refType() == null) {
            problems.add(new Problem(file, location, Code.MISSING_KEY,
                    "a field of type 'ref' must declare 'refType'"));
        }
        if (!isRef && field.refType() != null) {
            problems.add(new Problem(file, location, Code.INVALID_VALUE,
                    "'refType' is only valid on a field of type 'ref'"));
        }
        if ("code".equals(field.type()) && field.codeList().isEmpty()) {
            problems.add(new Problem(file, location, Code.MISSING_KEY,
                    "a field of type 'code' must declare a non-empty 'codeList'"));
        }
        if (!"code".equals(field.type()) && !field.codeList().isEmpty()) {
            problems.add(new Problem(file, location, Code.INVALID_VALUE,
                    "'codeList' is only valid on a field of type 'code'"));
        }
    }

    private void checkReferences(TypeDef type, Map<String, TypeDef> byName) {
        Path file = type.sourceFile();
        for (FieldDef field : type.fields()) {
            if (field.refType() != null && !byName.containsKey(field.refType())) {
                problems.add(new Problem(file, type.name() + "." + field.name(), Code.UNRESOLVED_REFERENCE,
                        "'refType' points at undeclared canonical type '" + field.refType() + "'"));
            }
        }
        for (RoleDef role : type.roles()) {
            TypeDef target = byName.get(role.targetType());
            if (target == null) {
                problems.add(new Problem(file, type.name() + "." + role.name(), Code.UNRESOLVED_REFERENCE,
                        "role target '" + role.targetType() + "' is not a declared canonical type"));
            } else if (target.kind() != TypeDef.Kind.ENTITY) {
                problems.add(new Problem(file, type.name() + "." + role.name(), Code.STRUCTURAL,
                        "role target '" + role.targetType() + "' must be an entity, not an association"));
            }
        }
    }
}
