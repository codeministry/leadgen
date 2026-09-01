package de.codeministry.leadgen;

import de.codeministry.leadgen.config.ConfigLoader;
import de.codeministry.leadgen.config.ConfigProperties;
import de.codeministry.leadgen.config.DotEnv;
import de.codeministry.leadgen.config.Secrets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

/**
 * Prints the configuration the process actually came up with, once, as one box.
 *
 * <p><b>Cumulative, not per file.</b> `application.yaml` and `.env` are two files with two
 * different readers, but nobody debugging a run thinks in files — they think "which database,
 * which mailbox, which model". So both are merged into one view and grouped by subject, and
 * every row says where its value came from instead of which list it was in.
 *
 * <p><b>Effective, not declared.</b> A `${POSTGRES_PORT:55432}` shows the port in use rather
 * than the expression, and a `.env` key a real environment variable overrides shows the value
 * that wins — otherwise the banner disagrees with the resolver exactly where it matters. The
 * keys declared with no value are kept: "declared and empty" is what someone is looking for
 * when the value is in the file and the service says it is missing.
 *
 * <p><b>Anything that looks like a credential is masked.</b> {@link Secrets} decides by key
 * name, at a fixed width, and separates "masked" from "not set": whether a secret is
 * configured at all is the one thing about it worth logging.
 *
 * <p><b>The two files have different reach, and the box says so.</b> `.env` is read by this
 * application's own placeholder resolver for the four `leadgen/*.yaml` files. Spring's own
 * `${...}` in `application.yaml` sees the process environment and not that file — which is
 * what Compose passes and what a shell export does. Same file, two consumers.
 *
 * <p>Same convention as {@link DatasourceBanner}: whatever a process reads that is
 * configurable, it names on startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigurationBanner {

    // Spring names the property sources it loads from a config file this way — application.yaml
    // on the classpath, and any external or profile-specific file layered over it. Matching the
    // prefix rather than a file name means a second file appears here without being listed.
    private static final String CONFIG_RESOURCE = "Config resource";

    /**
     * Framework plumbing that never varies and answers no question anyone opens this banner
     * with. Kept deliberately short: the rule is "does the value change what this run does",
     * and `flyway.enabled` passes it while `flyway.locations` does not.
     */
    private static final Pattern NOT_APP_RELEVANT =
            Pattern.compile("^spring\\.application\\.name$|^spring\\.flyway\\.locations$|^management\\.");

    /** `${VAR}` and `${VAR:default}`, the same shape the tool's own resolver reads. */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?}");

    /** Where a value won. The label is what the row carries, so it stays short. */
    private enum Origin {
        YAML("yaml"),
        DOTENV(".env"),
        PROCESS("env"),
        RESOLVED("path");

        private final String label;

        Origin(String label) {
            this.label = label;
        }
    }

    private record Entry(String key, String value, Origin origin) {}

    /**
     * A heading with the keys that belong under it. <b>First match wins</b>, so the order is
     * the classification: `DIGEST_DIR` is a digest setting and not a path, and `POSTGRES_PORT`
     * is a database setting and not a port. The last section matches everything, so a key
     * added tomorrow appears somewhere rather than disappearing.
     */
    private record Section(String icon, String title, Pattern keys) {
        Section(String icon, String title, String keys) {
            this(icon, title, Pattern.compile(keys, Pattern.CASE_INSENSITIVE));
        }
    }

    private static final List<Section> SECTIONS = List.of(
            new Section("🐘", "Database", "postgres|datasource|flyway"),
            new Section("📬", "Mail and sources", "imap|newsletter|mail"),
            new Section("🤖", "Language model", "llm"),
            new Section("📰", "Digest", "digest"),
            new Section("📝", "Logging", "log"),
            new Section("📁", "Paths", "leadgen|config|packages|inbox|dir"),
            new Section("🌐", "Server and web", "server|web|api|multipart|management|port|address"),
            new Section("🔐", "Security", "auth|security"),
            new Section("🔧", "Everything else", ".*"));

    private final ConfigurableEnvironment environment;
    private final ConfigProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void announce() {
        log.info("{}", describe(DotEnv.load()));
    }

    /**
     * One box and one log entry, rather than a line each: forty timestamped lines are a scroll,
     * and what this describes is read as a whole.
     *
     * <p>The `.env` is handed in rather than located here, so what is printed can be tested
     * without depending on which directory the test happens to run in — the same reason the
     * file is searched for upwards in the first place.
     */
    String describe(DotEnv dotenv) {
        List<Entry> entries = new ArrayList<>(fromApplicationYaml(dotenv));

        // Only the variables something in this process actually reads. Measured rather than
        // listed: a key is app-relevant if a `${...}` in application.yaml or in one of the
        // leadgen YAML files names it. `WEB_PORT` and `API_PROXY_TARGET` belong to the dev
        // server and to Compose, and a banner that shows them invites the reader to change
        // one and wait for an effect that cannot come.
        Set<String> read = referencedVariables();
        Set<String> shownAlready = referencedBy(applicationYamlNames());
        int ignored = 0;
        for (Map.Entry<String, String> declared : dotenv.declared().entrySet()) {
            String key = declared.getKey();
            if (!read.contains(key)) {
                ignored++;
                continue;
            }
            // A variable application.yaml refers to is already a row of its own, carrying the
            // value that won. Repeating it here would be the same setting twice, disagreeing.
            if (shownAlready.contains(key)) {
                continue;
            }
            String fromProcess = System.getenv(key);
            boolean overridden = fromProcess != null && !fromProcess.isBlank();
            entries.add(new Entry(
                    key,
                    Secrets.mask(key, overridden ? fromProcess : declared.getValue()),
                    overridden ? Origin.PROCESS : Origin.DOTENV));
        }

        entries.add(new Entry(
                "leadgen.config-dir → resolved",
                properties.configDirectory().toAbsolutePath()
                        + (Files.isDirectory(properties.configDirectory()) ? "" : "  (missing, defaults apply)"),
                Origin.RESOLVED));

        return render(entries, dotenv, ignored);
    }

    private List<Entry> fromApplicationYaml(DotEnv dotenv) {
        List<Entry> entries = new ArrayList<>();
        for (String name : applicationYamlNames()) {
            if (NOT_APP_RELEVANT.matcher(name).find()) {
                continue;
            }
            entries.add(new Entry(name, Secrets.mask(name, resolve(name)), origin(name, dotenv)));
        }
        return entries;
    }

    private TreeSet<String> applicationYamlNames() {
        var names = new TreeSet<String>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable
                    && enumerable.getName().startsWith(CONFIG_RESOURCE)) {
                names.addAll(Arrays.asList(enumerable.getPropertyNames()));
            }
        }
        return names;
    }

    /**
     * Which layer decided this property: a real environment variable, `.env`, or the default
     * written into the placeholder. The order is the precedence
     * {@link de.codeministry.leadgen.config.DotEnvEnvironmentPostProcessor} registers.
     */
    private Origin origin(String name, DotEnv dotenv) {
        Origin origin = Origin.YAML;
        for (String variable : placeholders(raw(name)).keySet()) {
            String fromProcess = System.getenv(variable);
            if (fromProcess != null && !fromProcess.isBlank()) {
                return Origin.PROCESS;
            }
            if (!dotenv.values().getOrDefault(variable, "").isEmpty()) {
                origin = Origin.DOTENV;
            }
        }
        return origin;
    }

    private String raw(String name) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source.getName().startsWith(CONFIG_RESOURCE) && source.containsProperty(name)) {
                return String.valueOf(source.getProperty(name));
            }
        }
        return "";
    }

    /** Every {@code ${VAR:default}} in a raw value, as variable to default (null if none). */
    private static Map<String, String> placeholders(String raw) {
        Map<String, String> found = new java.util.LinkedHashMap<>();
        var matcher = PLACEHOLDER.matcher(raw);
        while (matcher.find()) {
            found.put(matcher.group(1), matcher.group(2));
        }
        return found;
    }

    private Set<String> referencedBy(Iterable<String> names) {
        Set<String> variables = new LinkedHashSet<>();
        names.forEach(name -> variables.addAll(placeholders(raw(name)).keySet()));
        return variables;
    }

    /**
     * The variables this process can actually act on: those named by a placeholder in
     * `application.yaml` or in one of the four leadgen YAML files, in either layer.
     *
     * <p>Read from the raw text rather than from a list in here, for the same reason no CSS
     * selector is written in Java: a variable added to a YAML file tomorrow appears in the
     * banner without anyone remembering this class. A file that cannot be read contributes
     * nothing — a banner must not be able to take the process down at the last moment.
     */
    private Set<String> referencedVariables() {
        Set<String> variables = new LinkedHashSet<>(referencedBy(applicationYamlNames()));
        for (String file : List.of(
                ConfigLoader.PIPELINE_FILE,
                ConfigLoader.SOURCES_FILE,
                ConfigLoader.RULES_FILE,
                ConfigLoader.PROFILE_FILE)) {
            variables.addAll(placeholders(classpath("leadgen/" + file)).keySet());
            variables.addAll(placeholders(onDisk(properties.configDirectory().resolve(file))).keySet());
        }
        return variables;
    }

    private static String classpath(String resource) {
        try (var stream = ConfigurationBanner.class.getClassLoader().getResourceAsStream(resource)) {
            return stream == null ? "" : new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String onDisk(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * A placeholder with neither a value nor a default makes {@code getProperty} throw. That is
     * a real failure of the configuration, but it is not this class's to report: a banner that
     * can take the process down at the last moment before it is ready is worse than one line of
     * missing information.
     */
    private String resolve(String name) {
        try {
            return environment.getProperty(name);
        } catch (RuntimeException e) {
            return "(unresolved: " + e.getMessage() + ")";
        }
    }

    // ---- the box -------------------------------------------------------------------------
    // Drawn rather than logged line by line, so the whole thing survives being copied out of a
    // terminal in one piece. Every row is padded to a *display* width, which is not the string
    // length: an emoji is one code point and two columns, and getting that wrong tears the
    // right-hand border off exactly the lines that carry an icon.

    private static final int KEY_LIMIT = 46;
    private static final int VALUE_LIMIT = 56;

    private String render(List<Entry> entries, DotEnv dotenv, int ignored) {
        int keyWidth = entries.stream().mapToInt(e -> width(e.key())).max().orElse(0);
        int valueWidth = entries.stream().mapToInt(e -> width(e.value())).max().orElse(0);
        keyWidth = Math.min(keyWidth, KEY_LIMIT);
        valueWidth = Math.min(valueWidth, VALUE_LIMIT);
        int inner = 4 + keyWidth + 2 + valueWidth + 2 + 4 + 2;

        StringBuilder out = new StringBuilder();
        out.append("╭").append("─".repeat(inner)).append("╮");
        row(out, inner, "🧰  Effective configuration");
        row(out, inner, "   " + dotenv.file().map(Path::toString).orElse("no " + DotEnv.FILE_NAME + " found"));
        row(out, inner, "   yaml = application.yaml · .env = the file · env = process environment");
        if (ignored > 0) {
            row(out, inner, "   " + ignored + " further key(s) in .env are read by nothing in this process");
        }

        for (Section section : SECTIONS) {
            List<Entry> members = entries.stream()
                    .filter(entry -> section(entry.key()) == section)
                    .toList();
            if (members.isEmpty()) {
                continue;
            }
            out.append("\n├").append("─".repeat(inner)).append("┤");
            row(out, inner, section.icon() + "  " + section.title());
            for (Entry entry : members) {
                // A value longer than the column wraps rather than being cut: the one value
                // that reliably runs long is an absolute path, and its tail is the half that
                // says which directory it actually is.
                // Only the first line carries the key and the origin, so a value that had to
                // wrap still reads as one setting.
                List<String> lines = new ArrayList<>(wrap(entry.value(), valueWidth));
                if (lines.isEmpty()) {
                    lines.add("");
                }
                for (int i = 0; i < lines.size(); i++) {
                    row(
                            out,
                            inner,
                            "   " + pad(i == 0 ? clip(entry.key(), keyWidth) : "", keyWidth)
                                    + "  " + pad(lines.get(i), valueWidth)
                                    + "  " + (i == 0 ? entry.origin().label : ""));
                }
            }
        }
        out.append("\n╰").append("─".repeat(inner)).append("╯");
        return out.toString();
    }

    private static Section section(String key) {
        return SECTIONS.stream()
                .filter(candidate -> candidate.keys().matcher(key).find())
                .findFirst()
                .orElseThrow();
    }

    private static void row(StringBuilder out, int inner, String content) {
        out.append("\n│ ").append(pad(content, inner - 2)).append(" │");
    }

    private static String pad(String text, int to) {
        return text + " ".repeat(Math.max(0, to - width(text)));
    }

    /**
     * Wraps on spaces, and only breaks a word that is longer than the column on its own. The
     * one value that reliably runs long is an absolute path, which has no spaces to break on
     * and whose tail is the half that says which directory it actually is.
     */
    private static List<String> wrap(String text, int to) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            while (width(word) > to) {
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                lines.add(word.substring(0, to));
                word = word.substring(to);
            }
            if (width(line.toString()) + (line.isEmpty() ? 0 : 1) + width(word) > to) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(line.isEmpty() ? "" : " ").append(word);
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static String clip(String text, int to) {
        return width(text) <= to ? text : text.substring(0, Math.max(0, to - 1)) + "…";
    }

    /**
     * Columns, not characters: an emoji is one code point and two columns.
     *
     * <p>The section icons are deliberately taken from the block that has no text-presentation
     * past — no {@code U+FE0F} anywhere. A legacy symbol like {@code ⚙️} is one column in some
     * terminals and two in others, and either way the border is torn off exactly the rows that
     * carry an icon.
     */
    private static int width(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint == 0xFE0F || codePoint == 0x200D) {
                continue;
            }
            width += isWide(codePoint) ? 2 : 1;
        }
        return width;
    }

    private static boolean isWide(int codePoint) {
        // Only the emoji block, and only the part with no text-presentation past. A symbol
        // like `⚠` from the older blocks is one column here and two elsewhere, so nothing
        // printed by this class comes from there — see the icons above.
        return codePoint >= 0x1F300 && codePoint <= 0x1FAFF;
    }
}
