package de.codeministry.leadgen.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves `${VAR}` and `${VAR:default}` in the raw YAML text, before it is parsed.
 *
 * <p><b>Values come from the process environment first and from `.env` second</b>,
 * and the file is found by searching upwards from the working directory. That is what makes
 * the tool behave the same however it was started: Gradle's `bootRun` runs in `backend/`,
 * an IDE run configuration in the repository root, a jar wherever it sits, and Compose
 * passes real environment variables. Reading the file here rather than in the build means
 * no start path is privileged — the earlier version loaded it in a `bootRun` hook, so
 * launching the very same configuration from an IDE silently saw none of it.
 *
 * <p>Deliberately dumb about what it finds: an unresolved placeholder without a default
 * becomes an empty string rather than an error. Whether an empty value is acceptable is a
 * question about the field, not about the environment — an LLM key may be missing (the tool
 * runs without a model), the IMAP host of an enabled source may not. That judgement belongs
 * to validation, which can see which source is enabled.
 *
 * <p>Resolution runs on the text and not on the parsed tree because a placeholder may sit
 * anywhere, including inside a key or in a quoted regex. The pattern excludes `}` from the
 * variable name, so a regex like {@code (\d{1,3})} — braces but no `${` — is never touched.
 */
@Slf4j
final class PlaceholderResolver {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?}");

    /** The credentials file, and how far up it is looked for. */
    static final String DOTENV = ".env";

    private static final int SEARCH_DEPTH = 4;

    private final UnaryOperator<String> environment;

    PlaceholderResolver(UnaryOperator<String> environment) {
        this.environment = environment;
    }

    /** The process environment, with `.env.local` behind it. A real variable always wins. */
    static PlaceholderResolver fromSystemEnvironment() {
        Map<String, String> file = dotenv();
        return new PlaceholderResolver(name -> {
            String fromProcess = System.getenv(name);
            return fromProcess != null && !fromProcess.isBlank() ? fromProcess : file.get(name);
        });
    }

    private static Map<String, String> dotenv() {
        Path base = Path.of("").toAbsolutePath();
        for (int i = 0; i <= SEARCH_DEPTH && base != null; i++) {
            Path candidate = base.resolve(DOTENV);
            if (Files.isRegularFile(candidate)) {
                log.info("Reading {} for configuration values", candidate);
                return parse(candidate);
            }
            base = base.getParent();
        }
        log.info("No {} found above {} — only real environment variables apply", DOTENV, Path.of("").toAbsolutePath());
        return Map.of();
    }

    static Map<String, String> parse(Path file) {
        Map<String, String> values = new HashMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                String key = trimmed.substring(0, trimmed.indexOf('=')).trim();
                // A trailing comment is not part of the value, and a quoted value keeps its
                // spaces. Neither is exotic: the shipped template uses both.
                String raw = trimmed.substring(trimmed.indexOf('=') + 1).trim();
                if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() > 1) {
                    raw = raw.substring(1, raw.length() - 1);
                } else if (raw.contains("#")) {
                    raw = raw.substring(0, raw.indexOf('#')).trim();
                }
                if (!raw.isEmpty()) {
                    values.put(key, raw);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        return values;
    }

    String resolve(String raw) {
        Matcher matcher = PLACEHOLDER.matcher(raw);
        StringBuilder out = new StringBuilder();

        while (matcher.find()) {
            String name = matcher.group(1);
            String fallback = matcher.group(2);
            String value = environment.apply(name);

            if (value == null || value.isBlank()) {
                // An empty value is a non-statement, not a statement of "empty": a copied
                // template carries `IMAP_USER=`, and letting that beat the default would
                // break the run for no reason.
                value = fallback != null ? fallback : "";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
