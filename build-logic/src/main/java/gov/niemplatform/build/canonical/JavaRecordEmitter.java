package gov.niemplatform.build.canonical;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Emits Java records and their canonical descriptors from validated {@link TypeDef}s.
 *
 * <p>Spec §4.1 requires canonical types to be generated from a schema definition rather than
 * hand-written, so the DSL stays the single source of truth. Two things are generated per type:
 *
 * <ul>
 *   <li>an immutable Java record -- the typed materialisation used by projections and tests;
 *   <li>a {@code DESCRIPTOR} carrying NIEM provenance and extension justifications, which the
 *       contract layer (§4.2) derives schemas from and the catalogue (§4.8) registers.
 * </ul>
 *
 * <p>Generation also emits {@code fromRecord}/{@code toRecord} so the generic {@code Record}
 * that flows through the mapping DAG converts to and from the typed form without reflection.
 */
public final class JavaRecordEmitter {

    private static final String META = "gov.niemplatform.canonical.meta";
    private static final String DATA = "gov.niemplatform.canonical.data";

    private final String targetPackage;
    private final String catalogueClassName;

    public JavaRecordEmitter(String targetPackage, String catalogueClassName) {
        this.targetPackage = targetPackage;
        this.catalogueClassName = catalogueClassName;
    }

    /** Writes every generated source file under {@code outputDir}, returning the files written. */
    public List<Path> emit(List<TypeDef> types, Path outputDir) throws IOException {
        Path packageDir = outputDir.resolve(targetPackage.replace('.', '/'));
        Files.createDirectories(packageDir);

        List<Path> written = new ArrayList<>();
        for (TypeDef type : types) {
            Path file = packageDir.resolve(type.name() + ".java");
            Files.writeString(file, renderType(type), StandardCharsets.UTF_8);
            written.add(file);
        }
        Path catalogue = packageDir.resolve(catalogueClassName + ".java");
        Files.writeString(catalogue, renderCatalogue(types), StandardCharsets.UTF_8);
        written.add(catalogue);
        return written;
    }

    // --- type ------------------------------------------------------------

    private String renderType(TypeDef type) {
        StringBuilder out = new StringBuilder();
        header(out, type.sourceFile());
        out.append("package ").append(targetPackage).append(";\n\n");
        out.append("import ").append(DATA).append(".Record;\n");
        out.append("import ").append(META).append(".CanonicalFieldDescriptor;\n");
        out.append("import ").append(META).append(".CanonicalId;\n");
        out.append("import ").append(META).append(".CanonicalKind;\n");
        out.append("import ").append(META).append(".CanonicalRef;\n");
        out.append("import ").append(META).append(".CanonicalRoleDescriptor;\n");
        out.append("import ").append(META).append(".CanonicalTypeDescriptor;\n");
        out.append("import ").append(META).append(".ExtensionJustification;\n");
        out.append("import ").append(META).append(".FieldType;\n");
        out.append("import ").append(META).append(".NiemProvenance;\n\n");

        javadoc(out, "", type.doc(), type);

        out.append("public record ").append(type.name()).append("(\n");
        out.append(String.join(",\n", components(type)));
        out.append(") {\n\n");

        renderDescriptor(out, type);
        renderFromRecord(out, type);
        renderToRecord(out, type);

        out.append("}\n");
        return out.toString();
    }

    private List<String> components(TypeDef type) {
        List<String> parts = new ArrayList<>();
        parts.add("        /** Platform-assigned canonical identity. Never set by a mapping. */\n"
                + "        CanonicalId canonicalId");
        for (RoleDef role : type.roles()) {
            parts.add(memberDoc("        ", role.doc()) + "        CanonicalRef " + role.name());
        }
        for (FieldDef field : type.fields()) {
            parts.add(memberDoc("        ", field.doc()) + "        " + JavaTypeMapping.javaType(field)
                    + " " + field.name());
        }
        return parts;
    }

    private String memberDoc(String indent, String doc) {
        return doc == null || doc.isBlank() ? "" : indent + "/** " + escapeJavadoc(doc) + " */\n";
    }

    private void renderDescriptor(StringBuilder out, TypeDef type) {
        out.append("    /** Canonical metadata: NIEM provenance, extension justifications, and field shape. */\n");
        out.append("    public static final CanonicalTypeDescriptor DESCRIPTOR = new CanonicalTypeDescriptor(\n");
        out.append("            ").append(lit(type.name())).append(",\n");
        out.append("            ").append(lit(type.namespace())).append(",\n");
        out.append("            ").append(lit(type.version())).append(",\n");
        out.append("            CanonicalKind.").append(type.kind().name()).append(",\n");
        out.append("            ").append(provenance(type.provenance())).append(",\n");
        out.append("            ").append(extension(type.extension())).append(",\n");
        out.append("            ").append(fieldDescriptors(type)).append(",\n");
        out.append("            ").append(roleDescriptors(type)).append(");\n\n");
    }

    private String fieldDescriptors(TypeDef type) {
        if (type.fields().isEmpty()) {
            return "java.util.List.of()";
        }
        String entries = type.fields().stream()
                .map(f -> "                    new CanonicalFieldDescriptor("
                        + lit(f.name()) + ", FieldType." + JavaTypeMapping.fieldTypeConstant(f) + ", "
                        + f.required() + ", " + f.repeated() + ", "
                        + provenance(f.provenance()) + ", " + extension(f.extension()) + ", "
                        + stringList(f.codeList()) + ", " + lit(f.refType()) + ")")
                .collect(Collectors.joining(",\n"));
        return "java.util.List.of(\n" + entries + ")";
    }

    private String roleDescriptors(TypeDef type) {
        if (type.roles().isEmpty()) {
            return "java.util.List.of()";
        }
        String entries = type.roles().stream()
                .map(r -> "                    new CanonicalRoleDescriptor("
                        + lit(r.name()) + ", " + lit(r.targetType()) + ", "
                        + provenance(r.provenance()) + ", " + extension(r.extension()) + ")")
                .collect(Collectors.joining(",\n"));
        return "java.util.List.of(\n" + entries + ")";
    }

    private void renderFromRecord(StringBuilder out, TypeDef type) {
        out.append("    /**\n");
        out.append("     * Materialises the typed form from the generic record that flows through the mapping DAG.\n");
        out.append("     *\n");
        out.append("     * <p>Assumes contract validation (spec §4.2) has already passed for this record; a type\n");
        out.append("     * mismatch here is a platform bug, not bad source data, and fails loudly as such.\n");
        out.append("     */\n");
        out.append("    public static ").append(type.name()).append(" fromRecord(Record record) {\n");
        out.append("        return new ").append(type.name()).append("(\n");

        List<String> args = new ArrayList<>();
        args.add("                record.get(\"canonicalId\", CanonicalId.class)");
        for (RoleDef role : type.roles()) {
            args.add("                record.get(" + lit(role.name()) + ", CanonicalRef.class)");
        }
        for (FieldDef field : type.fields()) {
            String base = JavaTypeMapping.baseType(field);
            args.add(field.repeated()
                    ? "                record.getList(" + lit(field.name()) + ", " + base + ".class)"
                    : "                record.get(" + lit(field.name()) + ", " + base + ".class)");
        }
        out.append(String.join(",\n", args)).append(");\n");
        out.append("    }\n\n");
    }

    private void renderToRecord(StringBuilder out, TypeDef type) {
        out.append("    /** Converts back to the generic record form, e.g. for re-validation or quarantine. */\n");
        out.append("    public Record toRecord() {\n");
        out.append("        return Record.builder(DESCRIPTOR.qualifiedName())\n");
        out.append("                .set(\"canonicalId\", canonicalId)\n");
        for (RoleDef role : type.roles()) {
            out.append("                .set(").append(lit(role.name())).append(", ").append(role.name()).append(")\n");
        }
        for (FieldDef field : type.fields()) {
            out.append("                .set(").append(lit(field.name())).append(", ").append(field.name()).append(")\n");
        }
        out.append("                .build();\n");
        out.append("    }\n");
    }

    // --- catalogue -------------------------------------------------------

    private String renderCatalogue(List<TypeDef> types) {
        StringBuilder out = new StringBuilder();
        header(out, null);
        out.append("package ").append(targetPackage).append(";\n\n");
        out.append("import ").append(META).append(".CanonicalTypeDescriptor;\n");
        out.append("import java.util.List;\n");
        out.append("import java.util.Map;\n");
        out.append("import java.util.Optional;\n");
        out.append("import java.util.function.Function;\n");
        out.append("import java.util.stream.Collectors;\n\n");

        out.append("/**\n");
        out.append(" * Every canonical type generated from this module's DSL sources.\n");
        out.append(" *\n");
        out.append(" * <p>This is the registration point the catalogue (spec §4.8) and the contract layer\n");
        out.append(" * (§4.2) read from, so a type cannot exist in the model without being discoverable.\n");
        out.append(" */\n");
        out.append("public final class ").append(catalogueClassName).append(" {\n\n");

        String all = types.isEmpty()
                ? "List.of()"
                : "List.of(\n" + types.stream()
                        .map(t -> "            " + t.name() + ".DESCRIPTOR")
                        .collect(Collectors.joining(",\n")) + ")";
        out.append("    /** All canonical types in this module, in declaration order. */\n");
        out.append("    public static final List<CanonicalTypeDescriptor> ALL = ").append(all).append(";\n\n");

        out.append("    private static final Map<String, CanonicalTypeDescriptor> BY_NAME =\n");
        out.append("            ALL.stream().collect(Collectors.toUnmodifiableMap(\n");
        out.append("                    CanonicalTypeDescriptor::name, Function.identity()));\n\n");

        out.append("    private static final Map<String, CanonicalTypeDescriptor> BY_QUALIFIED_NAME =\n");
        out.append("            ALL.stream().collect(Collectors.toUnmodifiableMap(\n");
        out.append("                    CanonicalTypeDescriptor::qualifiedName, Function.identity()));\n\n");

        out.append("    private ").append(catalogueClassName).append("() {}\n\n");

        out.append("    /** Looks up a canonical type by its simple name, e.g. {@code \"Person\"}. */\n");
        out.append("    public static Optional<CanonicalTypeDescriptor> byName(String name) {\n");
        out.append("        return Optional.ofNullable(BY_NAME.get(name));\n");
        out.append("    }\n\n");

        out.append("    /** Looks up a canonical type by {@code namespace#Name}, as carried on every record. */\n");
        out.append("    public static Optional<CanonicalTypeDescriptor> byQualifiedName(String qualifiedName) {\n");
        out.append("        return Optional.ofNullable(BY_QUALIFIED_NAME.get(qualifiedName));\n");
        out.append("    }\n");
        out.append("}\n");
        return out.toString();
    }

    // --- literals --------------------------------------------------------

    private void header(StringBuilder out, Path source) {
        out.append("// Generated by niem-canonical-codegen. DO NOT EDIT.\n");
        if (source != null) {
            out.append("// Source: ").append(source.getFileName()).append("\n");
        }
        out.append("// Edit the canonical DSL and rebuild; this file is overwritten on every build.\n\n");
    }

    private void javadoc(StringBuilder out, String indent, String doc, TypeDef type) {
        out.append(indent).append("/**\n");
        out.append(indent).append(" * ").append(doc == null || doc.isBlank()
                ? "Canonical type " + type.name() + "." : escapeJavadoc(doc)).append("\n");
        out.append(indent).append(" *\n");
        if (type.provenance() != null) {
            out.append(indent).append(" * <p>NIEM provenance: {@code ")
                    .append(escapeJavadoc(type.provenance().niemType()))
                    .append("} in {@code ").append(escapeJavadoc(type.provenance().niemNamespace())).append("}.\n");
        } else {
            out.append(indent).append(" * <p><strong>Platform extension.</strong> ")
                    .append(escapeJavadoc(type.extension().justification())).append("\n");
        }
        out.append(indent).append(" *\n");
        out.append(indent).append(" * <p>Canonical version {@code ").append(type.version())
                .append("}, versioned independently of the platform (spec §7).\n");
        out.append(indent).append(" */\n");
    }

    private static String provenance(Provenance p) {
        return p == null
                ? "null"
                : "new NiemProvenance(" + lit(p.niemNamespace()) + ", " + lit(p.niemType()) + ", "
                        + lit(p.niemElement()) + ")";
    }

    private static String extension(Extension e) {
        return e == null ? "null" : "new ExtensionJustification(" + lit(e.justification()) + ")";
    }

    private static String stringList(List<String> values) {
        if (values.isEmpty()) {
            return "java.util.List.of()";
        }
        return "java.util.List.of(" + values.stream().map(JavaRecordEmitter::lit)
                .collect(Collectors.joining(", ")) + ")";
    }

    private static String lit(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String escapeJavadoc(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("@", "&#64;").replace("*/", "*&#47;");
    }
}
