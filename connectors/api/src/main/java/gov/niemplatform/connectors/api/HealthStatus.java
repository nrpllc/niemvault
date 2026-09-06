package gov.niemplatform.connectors.api;

import java.time.Instant;
import java.util.Objects;

/**
 * A connector's view of whether it can currently do its job (spec §4.3).
 *
 * <p>Deliberately distinct from whether records are flowing. A file drop connector pointed at an
 * empty directory is {@link State#HEALTHY} and idle; one pointed at a directory that no longer
 * exists is {@link State#UNAVAILABLE}. Conflating the two is how a misconfigured source sits
 * quietly reporting nothing wrong.
 *
 * @param state current state
 * @param detail human-readable explanation, always populated for anything but HEALTHY
 * @param checkedAt when the check ran
 */
public record HealthStatus(State state, String detail, Instant checkedAt) {

    /** Connector states, ordered from good to bad. */
    public enum State {
        /** Configured and able to reach its source. */
        HEALTHY,
        /** Working, but something is wrong that an operator should see. */
        DEGRADED,
        /** Cannot reach its source. */
        UNAVAILABLE,
        /** Not configured yet; {@code configure} has not been called or it failed. */
        NOT_CONFIGURED
    }

    public HealthStatus {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(checkedAt, "checkedAt");
        if (state != State.HEALTHY && (detail == null || detail.isBlank())) {
            throw new IllegalArgumentException(
                    "A " + state + " health status must explain itself");
        }
    }

    public static HealthStatus healthy(Instant checkedAt) {
        return new HealthStatus(State.HEALTHY, null, checkedAt);
    }

    public static HealthStatus degraded(String detail, Instant checkedAt) {
        return new HealthStatus(State.DEGRADED, detail, checkedAt);
    }

    public static HealthStatus unavailable(String detail, Instant checkedAt) {
        return new HealthStatus(State.UNAVAILABLE, detail, checkedAt);
    }

    public static HealthStatus notConfigured(Instant checkedAt) {
        return new HealthStatus(State.NOT_CONFIGURED, "configure() has not completed", checkedAt);
    }

    public boolean isHealthy() {
        return state == State.HEALTHY;
    }
}
