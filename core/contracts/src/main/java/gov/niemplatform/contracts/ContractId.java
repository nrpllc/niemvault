package gov.niemplatform.contracts;

import java.io.Serializable;
import java.util.Objects;

/**
 * Identity of a hop contract.
 *
 * <p>Contracts are versioned artifacts on disk (spec §4.2), and content versions move
 * independently of the platform (§7). A contract is therefore addressed by name <em>and</em>
 * version everywhere -- including in lineage and in violation events, so an auditor asking
 * which contract rejected a record in March gets an answer that survives later edits.
 *
 * @param name stable contract name, e.g. {@code cad-person-to-canonical}
 * @param version content version, semver
 */
public record ContractId(String name, String version) implements Serializable {

    public ContractId {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        if (name.isBlank()) {
            throw new IllegalArgumentException("A contract name must not be blank");
        }
        if (!version.matches("\\d+\\.\\d+\\.\\d+")) {
            throw new IllegalArgumentException(
                    "A contract version must be semver (MAJOR.MINOR.PATCH), found '" + version + "'");
        }
    }

    public static ContractId of(String name, String version) {
        return new ContractId(name, version);
    }

    @Override
    public String toString() {
        return name + "@" + version;
    }
}
