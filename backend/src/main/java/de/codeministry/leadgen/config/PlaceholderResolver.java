package de.codeministry.leadgen.config;

import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves `${VAR}` and `${VAR:default}` in the raw YAML text, before it is parsed.
 *
 * <p>Deliberately dumb: an unresolved placeholder without a default becomes an empty
 * string rather than an error. Whether an empty value is acceptable is a question
 * about the field, not about the environment — an LLM key may be missing (the tool
 * runs without a model), the IMAP host of an enabled source may not. That judgement
 * belongs to validation, which can see which source is enabled.
 *
 * <p>Resolution runs on the text and not on the parsed tree because a placeholder may
 * sit anywhere, including inside a key or in a quoted regex. The pattern deliberately
 * excludes `}` from the variable name so a regex like {@code (\d{1,3})} — which
 * contains braces but no `${` — is never touched.
 */
final class PlaceholderResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?}");

    private final UnaryOperator<String> environment;

    PlaceholderResolver(UnaryOperator<String> environment) {
        this.environment = environment;
    }

    static PlaceholderResolver fromSystemEnvironment() {
        return new PlaceholderResolver(System::getenv);
    }

    String resolve(String raw) {
        Matcher matcher = PLACEHOLDER.matcher(raw);
        StringBuilder out = new StringBuilder();

        while (matcher.find()) {
            String name = matcher.group(1);
            String fallback = matcher.group(2);
            String value = environment.apply(name);

            if (value == null || value.isBlank()) {
                // An empty environment variable is a non-statement, not a statement of
                // "empty": a .env copied from the template carries `IMAP_USER=`, and
                // letting that beat the default would break the run for no reason.
                value = fallback != null ? fallback : "";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
