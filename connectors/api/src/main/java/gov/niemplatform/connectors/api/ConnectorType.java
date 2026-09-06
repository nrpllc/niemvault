package gov.niemplatform.connectors.api;

import java.io.Serializable;
import java.util.Objects;

/**
 * Identifies a kind of transport, e.g. {@code file-drop}, {@code kafka}, {@code cdc}.
 *
 * <p>A value type rather than an enum, because connectors are discovered through the service
 * loader (spec §4.3) and an agency may ship one the platform has never heard of. An enum would
 * make the set closed, which is precisely what an SPI must not be.
 */
public record ConnectorType(String id) implements Serializable {

    public ConnectorType {
        Objects.requireNonNull(id, "id");
        if (!id.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException(
                    "A connector type must be lower-case kebab-case, found '" + id + "'");
        }
    }

    public static ConnectorType of(String id) {
        return new ConnectorType(id);
    }

    @Override
    public String toString() {
        return id;
    }
}
