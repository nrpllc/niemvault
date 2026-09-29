package gov.niemplatform.settings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a settings map against a component's declared settings, before anything is configured.
 *
 * <p>A pre-flight for authoring, not a replacement for the component's own checks. Configuring a
 * connector is still the authority on whether its settings work -- this catches what a form can
 * catch as it is typed (a missing required value, a misspelled key, "ten" where a number goes) and,
 * for projections, what could otherwise only be learned by opening one, which connects to a live
 * store and claims it for a tenant (ADR 0026) and so is never done from an authoring surface.
 */
public final class SettingsCheck {

    private SettingsCheck() {}

    /** One problem with one setting. */
    public record Problem(String key, String detail) {
        @Override
        public String toString() {
            return "'" + key + "': " + detail;
        }
    }

    /**
     * Every problem at once.
     *
     * <p>A setting whose sensitivity is {@link SettingDescriptor.Sensitivity#SECRET_VALUE} is refused
     * when it carries a value: this check runs on what is about to be written into a versioned
     * artifact, and ADR 0015 keeps secrets out of those. A deployment supplies such a value at the
     * point it runs.
     */
    public static List<Problem> check(List<SettingDescriptor> declared, Map<String, String> settings) {
        List<Problem> problems = new ArrayList<>();
        if (declared.isEmpty()) {
            // A component that describes nothing is not checked here -- its own configure() is the
            // only authority, and guessing would refuse settings it accepts.
            return problems;
        }
        Set<String> known = new LinkedHashSet<>();
        for (SettingDescriptor setting : declared) {
            known.add(setting.key());
            String raw = settings.get(setting.key());
            String value = raw == null ? null : raw.trim();
            boolean present = value != null && !value.isEmpty();

            if (setting.sensitivity() == SettingDescriptor.Sensitivity.SECRET_VALUE && present) {
                problems.add(new Problem(setting.key(), "is a secret this component reads as a literal "
                        + "value; it must not be written into a versioned artifact (ADR 0015). Leave it "
                        + "empty here and supply it where the pipeline is deployed"));
                continue;
            }
            if (!present) {
                if (setting.required() && setting.sensitivity() != SettingDescriptor.Sensitivity.SECRET_VALUE) {
                    problems.add(new Problem(setting.key(), "is required"));
                }
                continue;
            }
            switch (setting.kind()) {
                case INTEGER -> {
                    try {
                        Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        problems.add(new Problem(setting.key(), "must be a whole number, found '" + value + "'"));
                    }
                }
                case BOOLEAN -> {
                    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                        problems.add(new Problem(setting.key(), "must be true or false, found '" + value + "'"));
                    }
                }
                case CHOICE -> {
                    if (setting.options().stream().noneMatch(option -> option.equalsIgnoreCase(value))) {
                        problems.add(new Problem(setting.key(), "must be one of " + setting.options()
                                + ", found '" + value + "'"));
                    }
                }
                case TEXT -> {
                    if (setting.sensitivity() == SettingDescriptor.Sensitivity.ENV_VAR_NAME
                            && !value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                        problems.add(new Problem(setting.key(), "names the environment variable holding "
                                + "the secret, never the secret itself, and what was given is not a "
                                + "variable name"));
                    }
                }
                default -> throw new IllegalStateException("unhandled kind " + setting.kind());
            }
        }
        settings.keySet().stream()
                .filter(key -> !known.contains(key))
                .sorted()
                .forEach(key -> problems.add(new Problem(key,
                        "is not a setting this component reads; it accepts " + known)));
        return problems;
    }
}
