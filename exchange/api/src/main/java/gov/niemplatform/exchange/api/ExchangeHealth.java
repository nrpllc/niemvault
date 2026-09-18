package gov.niemplatform.exchange.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Whether an exchange can currently reach the system it submits to (ADR 0034).
 *
 * <p>Distinct from whether anything is being submitted, for the reason connector health is: a
 * repository with nothing queued for it is reachable and idle, which is not a problem. One that
 * cannot be reached is, and saying so before a run starts is the difference between an operator
 * fixing a certificate and an operator working out how much of a submission got through.
 */
public record ExchangeHealth(State state, String detail, Instant at) {

    public enum State { HEALTHY, DEGRADED, UNAVAILABLE, NOT_CONFIGURED }

    public ExchangeHealth {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(at, "at");
    }

    public boolean isHealthy() {
        return state == State.HEALTHY;
    }

    public static ExchangeHealth healthy(Instant at) {
        return new ExchangeHealth(State.HEALTHY, null, at);
    }

    public static ExchangeHealth degraded(String detail, Instant at) {
        return new ExchangeHealth(State.DEGRADED, detail, at);
    }

    public static ExchangeHealth unavailable(String detail, Instant at) {
        return new ExchangeHealth(State.UNAVAILABLE, detail, at);
    }

    public static ExchangeHealth notConfigured(Instant at) {
        return new ExchangeHealth(State.NOT_CONFIGURED, "not configured", at);
    }

    @Override
    public String toString() {
        return state + (detail == null ? "" : ": " + detail);
    }
}
