package de.codeministry.leadgen;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.config.ConfigProperties;
import de.codeministry.leadgen.config.DotEnv;
import de.codeministry.leadgen.config.Secrets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class ConfigurationBannerTest {

    private final StandardEnvironment environment = new StandardEnvironment();

    /**
     * The fixture carries raw values with placeholders in them, because that is what a property
     * source loaded from a file holds — the banner reads both the raw form (to see which
     * variables a property depends on) and the resolved one.
     */
    private ConfigurationBanner banner() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("server.port", "8080");
        properties.put("spring.datasource.url", "jdbc:postgresql://localhost:55432/leadgen");
        properties.put("spring.datasource.password", "hunter2");
        properties.put("leadgen.config-dir", "${LEADGEN_CONFIG_DIR:config}");
        // PATH is the one variable that is certainly set wherever this test runs.
        properties.put("leadgen.packages-dir", "${PATH:../packages}");

        // Spring names a property source loaded from a config file exactly this way, and the
        // banner matches on that prefix — so the fixture has to carry the real name.
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "Config resource 'class path resource [application.yaml]'", properties));
        return new ConfigurationBanner(environment, new ConfigProperties("config"));
    }

    private static DotEnv dotenv(String... pairs) {
        Map<String, String> declared = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            declared.put(pairs[i], pairs[i + 1]);
        }
        return new DotEnv(Optional.of(Path.of("/somewhere/.env")), declared);
    }

    @Test
    void printsEveryKeyOfTheConfigurationFile() {
        String text = banner().describe(dotenv());

        assertThat(text).contains("server.port", "8080");
        assertThat(text).contains("spring.datasource.url", "jdbc:postgresql://localhost:55432/leadgen");
        assertThat(text).contains("leadgen.config-dir");
    }

    @Test
    void masksThePassword() {
        String text = banner().describe(dotenv());

        assertThat(text).doesNotContain("hunter2");
        assertThat(text).contains("spring.datasource.password").contains(Secrets.MASK);
    }

    @Test
    void printsTheDotenvFileWithItsSecretsMasked() {
        // All three are named by a `${...}` in the shipped leadgen YAML files, which is what
        // makes them app-relevant.
        String text = banner()
                .describe(dotenv("IMAP_HOST", "imap.example.org", "IMAP_PASSWORD", "s3cr3t", "LLM_API_KEY", ""));

        assertThat(text).contains("/somewhere/.env");
        assertThat(text).contains("IMAP_HOST", "imap.example.org");
        assertThat(text).doesNotContain("s3cr3t");
        // An unset key and a masked one must not look alike — "is it set" is the point.
        assertThat(text).contains("LLM_API_KEY", Secrets.EMPTY);
    }

    @Test
    void leavesOutTheKeysNothingInThisProcessReads() {
        // WEB_PORT belongs to the dev server and API_PROXY_TARGET to Compose. Showing them
        // would invite the reader to change one and wait for an effect that cannot come.
        String text = banner().describe(dotenv("WEB_PORT", "4200", "IMAP_HOST", "imap.example.org"));

        assertThat(text).doesNotContain("WEB_PORT").contains("IMAP_HOST");
        assertThat(text).contains("1 further key(s)");
    }

    @Test
    void showsAVariableOnceEvenWhenBothFilesNameIt() {
        // `leadgen.config-dir` already carries what LEADGEN_CONFIG_DIR was allowed to decide.
        // A second row for the variable itself would be the same setting twice, disagreeing.
        String text = banner().describe(dotenv("LEADGEN_CONFIG_DIR", "config"));

        assertThat(text).contains("leadgen.config-dir");
        assertThat(text.lines().filter(line -> line.contains("LEADGEN_CONFIG_DIR")).count())
                .isZero();
    }

    @Test
    void marksAValueTheDotEnvFileDecided() {
        // `.env` is a property source now, so a value written there really does decide the
        // property — and the row has to name the layer it came from rather than "yaml".
        String row = banner().describe(dotenv("LEADGEN_CONFIG_DIR", "./config/local")).lines()
                .filter(line -> line.contains("leadgen.config-dir "))
                .findFirst()
                .orElseThrow();

        assertThat(row).contains(".env");
    }

    @Test
    void marksAValueTheProcessEnvironmentDecided() {
        String row = banner().describe(dotenv()).lines()
                .filter(line -> line.contains("leadgen.packages-dir"))
                .findFirst()
                .orElseThrow();

        // `env`, not `yaml`: PATH is set, so the placeholder's default never applied.
        assertThat(row).matches(".*[^.]env\\s*│");
    }

    @Test
    void namesWhereARelativeConfigDirectoryEndedUp() {
        String text = banner().describe(dotenv());

        assertThat(text).contains("resolved").contains(Path.of("config").toAbsolutePath().getFileName().toString());
    }

    @Test
    void groupsBothFilesUnderOneHeadingPerSubject() {
        // The point of the box: what belongs together stands together, whichever file it
        // came from and whichever naming convention it follows.
        String text = banner().describe(dotenv("IMAP_HOST", "imap.example.org"));

        int mail = text.indexOf("Mail and sources");
        int database = text.indexOf("Database");
        assertThat(mail).isPositive();
        assertThat(text.indexOf("IMAP_HOST")).isGreaterThan(mail);
        assertThat(text.indexOf("spring.datasource.url")).isGreaterThan(database).isLessThan(mail);
    }

    @Test
    void everyLineOfTheBoxIsTheSameWidth() {
        String[] lines = banner()
                .describe(dotenv("IMAP_HOST", "imap.example.org", "LLM_PROVIDER", ""))
                .split("\n");

        // Measured the way a terminal measures: the icons are two columns, not one character.
        long widths = Arrays.stream(lines).mapToInt(ConfigurationBannerTest::columns).distinct().count();
        assertThat(widths).as("every line: %s", Arrays.toString(lines)).isEqualTo(1);
    }

    private static int columns(String line) {
        int width = 0;
        for (int i = 0; i < line.length(); ) {
            int codePoint = line.codePointAt(i);
            i += Character.charCount(codePoint);
            width += codePoint >= 0x1F300 && codePoint <= 0x1FAFF ? 2 : 1;
        }
        return width;
    }
}
