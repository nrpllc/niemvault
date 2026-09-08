package gov.niemplatform.build.canonical;

import org.gradle.api.provider.Property;

/** Per-module configuration for canonical code generation. */
public interface CanonicalModelExtension {

    /** Java package the generated records land in. */
    Property<String> getTargetPackage();

    /** Class name of the generated per-module type catalogue. */
    Property<String> getCatalogueClassName();

    /**
     * Namespace prefix reserved for platform extensions.
     *
     * <p>Spec §4.1: extensions live in a separate namespace from NIEM-sourced types. The
     * generator enforces that in both directions -- an extension outside this prefix and a
     * NIEM-sourced type inside it are both build failures.
     */
    Property<String> getExtensionNamespaceRoot();
}
