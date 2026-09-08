package gov.niemplatform.build.canonical;

/**
 * Maps canonical DSL types onto Java types.
 *
 * <p>All generated components are boxed and nullable. Absence is represented by {@code null};
 * "required" is a contract-layer assertion (spec §4.2), not a language-level one, because a
 * missing required field must produce a {@code ContractViolation} and a quarantined record --
 * not a constructor that throws somewhere inside a Flink operator.
 */
final class JavaTypeMapping {

    private JavaTypeMapping() {}

    /** Java type for a field, accounting for {@code repeated}. */
    static String javaType(FieldDef field) {
        String base = baseType(field);
        return field.repeated() ? "java.util.List<" + base + ">" : base;
    }

    static String baseType(FieldDef field) {
        if ("ref".equals(field.type())) {
            return "gov.niemplatform.canonical.meta.CanonicalRef";
        }
        return switch (field.type()) {
            case "string", "code" -> "String";
            case "date" -> "java.time.LocalDate";
            case "dateTime" -> "java.time.Instant";
            case "integer" -> "Long";
            case "decimal" -> "java.math.BigDecimal";
            case "boolean" -> "Boolean";
            default -> throw new IllegalStateException("unmapped canonical type: " + field.type());
        };
    }

    /** The {@code FieldType} enum constant describing this field to the contract layer. */
    static String fieldTypeConstant(FieldDef field) {
        return switch (field.type()) {
            case "string" -> "STRING";
            case "code" -> "CODE";
            case "date" -> "DATE";
            case "dateTime" -> "DATE_TIME";
            case "integer" -> "INTEGER";
            case "decimal" -> "DECIMAL";
            case "boolean" -> "BOOLEAN";
            case "ref" -> "REF";
            default -> throw new IllegalStateException("unmapped canonical type: " + field.type());
        };
    }
}
