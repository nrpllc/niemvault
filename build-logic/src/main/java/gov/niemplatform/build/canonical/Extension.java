package gov.niemplatform.build.canonical;

/**
 * Marks a canonical type, field, or role as a platform extension rather than NIEM-sourced.
 *
 * <p>Spec §4.1 requires a written justification for every extension. The justification is
 * carried into the generated descriptor so the catalogue (§4.8) can surface it without
 * reading the DSL sources.
 */
public record Extension(String justification) {}
