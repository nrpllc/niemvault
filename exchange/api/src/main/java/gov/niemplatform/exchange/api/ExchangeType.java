package gov.niemplatform.exchange.api;

import java.io.Serializable;
import java.util.Objects;

/**
 * Identifies a kind of outbound exchange, e.g. {@code cch-http}, {@code nibrs-file}.
 *
 * <p>A value type rather than an enum, for the reason {@link gov.niemplatform.exchange.api} exists
 * at all: a state repository's wire format is that state's business, and an agency must be able to
 * ship one this platform has never heard of. An enum would make the set closed, which is what an
 * SPI must not be -- and closing it is how "add a field to the submission" became a platform
 * release.
 */
public record ExchangeType(String id) implements Serializable {

    public ExchangeType {
        Objects.requireNonNull(id, "id");
        if (!id.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException(
                    "An exchange type must be lower-case kebab-case, found '" + id + "'");
        }
    }

    public static ExchangeType of(String id) {
        return new ExchangeType(id);
    }

    @Override
    public String toString() {
        return id;
    }
}
