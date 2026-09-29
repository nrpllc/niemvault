package gov.niemplatform.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One setting a pluggable component reads, as that component declares it (ADR 0037).
 *
 * <p>Declared by the implementation rather than written into an authoring surface. A form typed out
 * by hand in the browser would be a second copy of what a connector accepts, and the first time a
 * connector gained a setting the form would quietly stop offering it -- or keep offering one the
 * connector had stopped reading. Each implementation's tests assert its descriptors name exactly the
 * keys it accepts, so the two cannot drift apart.
 *
 * <h2>Secrets</h2>
 *
 * <p>ADR 0015 keeps credentials out of versioned artifacts. {@link Sensitivity} says how a setting
 * relates to that rule, because the components do not all handle it the same way yet:
 *
 * <ul>
 *   <li>{@link Sensitivity#PLAIN} -- an ordinary value.
 *   <li>{@link Sensitivity#ENV_VAR_NAME} -- the value is the <em>name</em> of an environment variable
 *       that holds the secret ({@code passwordEnv}, {@code tokenEnv}). Safe to write into a file.
 *   <li>{@link Sensitivity#SECRET_VALUE} -- the component reads the secret itself from this
 *       setting. An authoring surface must never write one into an artifact; the deployment
 *       supplies it.
 * </ul>
 */
public record SettingDescriptor(
        String key,
        String label,
        String description,
        Kind kind,
        boolean required,
        String defaultValue,
        List<String> options,
        Sensitivity sensitivity) {

    /** What kind of value a setting takes. Every setting is text on disk; this is how it parses. */
    public enum Kind {
        TEXT,
        INTEGER,
        BOOLEAN,
        /** One of {@link #options()}. */
        CHOICE
    }

    /** How a setting relates to ADR 0015. See the class comment. */
    public enum Sensitivity {
        PLAIN,
        ENV_VAR_NAME,
        SECRET_VALUE
    }

    public SettingDescriptor {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sensitivity, "sensitivity");
        label = label == null || label.isBlank() ? key : label;
        description = description == null ? "" : description;
        options = options == null ? List.of() : List.copyOf(options);
        if (kind == Kind.CHOICE && options.isEmpty()) {
            throw new IllegalArgumentException("choice setting '" + key + "' declares no options");
        }
    }

    public Optional<String> defaultIfAbsent() {
        return Optional.ofNullable(defaultValue);
    }

    // --- builders ---------------------------------------------------------------------------

    public static Builder text(String key) {
        return new Builder(key, Kind.TEXT);
    }

    public static Builder integer(String key) {
        return new Builder(key, Kind.INTEGER);
    }

    public static Builder bool(String key) {
        return new Builder(key, Kind.BOOLEAN);
    }

    public static Builder choice(String key, String... options) {
        return new Builder(key, Kind.CHOICE).options(List.of(options));
    }

    /** Fluent construction, so a component's declaration reads as a list of its settings. */
    public static final class Builder {
        private final String key;
        private final Kind kind;
        private String label;
        private String description;
        private boolean required;
        private String defaultValue;
        private List<String> options = List.of();
        private Sensitivity sensitivity = Sensitivity.PLAIN;

        private Builder(String key, Kind kind) {
            this.key = key;
            this.kind = kind;
        }

        public Builder label(String value) {
            this.label = value;
            return this;
        }

        public Builder describe(String value) {
            this.description = value;
            return this;
        }

        public Builder required() {
            this.required = true;
            return this;
        }

        public Builder defaultsTo(String value) {
            this.defaultValue = value;
            return this;
        }

        public Builder options(List<String> values) {
            this.options = values;
            return this;
        }

        public Builder envVarName() {
            this.sensitivity = Sensitivity.ENV_VAR_NAME;
            return this;
        }

        public Builder secretValue() {
            this.sensitivity = Sensitivity.SECRET_VALUE;
            return this;
        }

        public SettingDescriptor build() {
            return new SettingDescriptor(
                    key, label, description, kind, required, defaultValue, options, sensitivity);
        }
    }
}
