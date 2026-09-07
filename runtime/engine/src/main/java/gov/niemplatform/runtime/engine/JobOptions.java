package gov.niemplatform.runtime.engine;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * How a mapping run should execute, beyond the mapping itself.
 *
 * <p>Deliberately separate from {@link MappingDefinition}: none of this changes what the mapping
 * produces. A run with the dashboard on and a run without it are the same transformation, and
 * acceptance criterion 7 would be meaningless if an execution option could alter the output.
 *
 * @param mode batch or streaming; the only thing that differs between the two modalities
 * @param webUiPort port for Flink's job-graph dashboard, or {@code null} to leave it off
 * @param pace delay inserted between source records, or {@code null} for no throttle
 */
public record JobOptions(ExecutionMode mode, Integer webUiPort, Duration pace) {

    /** Flink's conventional dashboard port. */
    public static final int DEFAULT_WEB_UI_PORT = 8081;

    public JobOptions {
        Objects.requireNonNull(mode, "mode");
        if (webUiPort != null && (webUiPort < 1 || webUiPort > 65535)) {
            throw new IllegalArgumentException("Dashboard port must be 1-65535, found " + webUiPort);
        }
        if (pace != null && pace.isNegative()) {
            throw new IllegalArgumentException("Pace cannot be negative");
        }
    }

    /** Run as fast as possible, no dashboard. What a scheduled job wants. */
    public static JobOptions of(ExecutionMode mode) {
        return new JobOptions(mode, null, null);
    }

    /**
     * Run with the dashboard bound and the source throttled.
     *
     * <p>The throttle is what makes the dashboard worth opening. A fifteen-record run finishes in
     * well under a second, and the mini cluster stops with it -- a dashboard that appears and
     * disappears before the page loads is not a dashboard. Pacing the source stretches the run
     * long enough to watch records move through the graph.
     */
    public static JobOptions withDashboard(ExecutionMode mode, int port, Duration pace) {
        return new JobOptions(mode, port, pace);
    }

    public Optional<Integer> dashboardPort() {
        return Optional.ofNullable(webUiPort);
    }

    public Optional<Duration> throttle() {
        return Optional.ofNullable(pace).filter(duration -> !duration.isZero());
    }
}
