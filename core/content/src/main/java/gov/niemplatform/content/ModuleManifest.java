package gov.niemplatform.content;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * What a domain module declares about itself (spec §7).
 *
 * <p>Every domain module ships one. It is the content metadata §7 requires compatibility ranges to
 * live in, and it is the reason a second domain module can be added without discovering by
 * experiment which platform releases it works on.
 *
 * <p>It is also where cross-domain consistency actually comes from. A module names the canonical
 * namespaces it contributes and the canonical model version it was authored against; two modules
 * claiming the same namespace, or a module written against a canonical model the deployment does
 * not have, are both detectable at load rather than at first record.
 *
 * @param name module identifier, stable across versions, e.g. {@code law-enforcement}
 * @param version content version of this module, moving independently of the platform
 * @param displayName human-readable name for the catalogue and the CLI
 * @param description what the module is for
 * @param platformVersions platform releases this content declares it works with
 * @param canonicalModelVersion the canonical model version the content was authored against
 * @param canonicalNamespaces namespaces this module contributes types to; empty when it contributes
 *     none and only maps onto the core model
 * @param steward who owns this module's content, for the stewardship routing §4.8 describes
 */
public record ModuleManifest(
        String name,
        SemanticVersion version,
        String displayName,
        String description,
        VersionRange platformVersions,
        SemanticVersion canonicalModelVersion,
        List<String> canonicalNamespaces,
        String steward) implements Serializable {

    public ModuleManifest {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(platformVersions, "platformVersions");
        Objects.requireNonNull(canonicalModelVersion, "canonicalModelVersion");
        canonicalNamespaces = List.copyOf(canonicalNamespaces);

        if (!name.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException(
                    "A module name must be lower-case kebab-case, found '" + name + "'");
        }
    }

    /** Identifier carried in the catalogue and in events: {@code name@version}. */
    public String qualifiedName() {
        return name + "@" + version;
    }

    /**
     * Whether this content will load against a given platform release.
     *
     * <p>Checked before anything else in the module is read. Loading a mapping and discovering the
     * incompatibility three artifacts later would leave the operator guessing which one was at
     * fault.
     */
    public boolean supports(SemanticVersion platformVersion) {
        return platformVersions.includes(platformVersion);
    }
}
