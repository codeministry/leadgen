package de.codeministry.leadgen.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @TempDir
    Path configDir;

    @BeforeEach
    void materializeTheShippedExamples() {
        ConfigFixtures.materialize(configDir);
    }

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Test
    void loadsTheShippedExamples() {
        var snapshot = ConfigFixtures.loaderFor(configDir, VALIDATOR).load();

        assertThat(snapshot.application().version()).isEqualTo(1);
        assertThat(snapshot.application().enrichment().fetch().timeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(snapshot.application().enrichment().fetch().cacheTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(snapshot.rules().hardFilters().remote().minRemotePercent()).isEqualTo(80);
        assertThat(snapshot.rules().scoring().weights()).containsEntry("core_skill_overlap", 45);
        assertThat(snapshot.sources().sources()).extracting("id").contains("sample-newsletter");
        assertThat(snapshot.sources().connections()).hasSize(1);
    }

    @Test
    void resolvesEnvironmentPlaceholdersAndTheirDefaults() {
        var env = Map.of("IMAP_HOST", "imap.example.org", "IMAP_USER", "someone");
        var snapshot = ConfigFixtures.loaderFor(configDir, VALIDATOR, env).load();
        var connection = snapshot.sources().connections().getFirst();

        assertThat(connection.host()).isEqualTo("imap.example.org");
        assertThat(connection.username()).isEqualTo("someone");
        assertThat(connection.port()).isEqualTo(993); // ${IMAP_PORT:993}
        // Neither variable nor default: the placeholder resolves to nothing, and an
        // empty YAML scalar is null — not "". Every consumer has to treat both alike.
        assertThat(connection.password()).isNull();
    }

    @Test
    void rejectsAnUnknownKey() throws IOException {
        // A misspelled rule would otherwise disable a hard filter in silence — the only
        // visible effect is a longer shortlist, which looks like a good day on the market.
        rewrite("matching-rules.yaml", "min_remote_percent", "min_remote_percentage");

        assertThatThrownBy(() -> ConfigFixtures.loaderFor(configDir, VALIDATOR).load())
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("min_remote_percentage");
    }

    @Test
    void rejectsTheRateFilterBeforeEnrichment() throws IOException {
        rewrite("matching-rules.yaml", "apply_after: enrichment", "apply_after: hard_filter");

        assertThatThrownBy(() -> ConfigFixtures.loaderFor(configDir, VALIDATOR).load())
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("only 'enrichment' is allowed");
    }

    @Test
    void rejectsASourceNamingAnUndeclaredConnection() throws IOException {
        rewrite("sources.yaml", "connection: mailbox-primary", "connection: mailbox-typo");

        assertThatThrownBy(() -> ConfigFixtures.loaderFor(configDir, VALIDATOR).load())
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("'mailbox-typo', which is not declared");
    }

    @Test
    void rejectsAnEnabledMailSourceWithoutCredentials() throws IOException {
        rewrite("sources.yaml", "id: sample-newsletter\n    enabled: false", "id: sample-newsletter\n    enabled: true");

        assertThatThrownBy(() -> ConfigFixtures.loaderFor(configDir, VALIDATOR).load())
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("IMAP_HOST");
    }

    @Test
    void acceptsAnEnabledMailSourceOnceTheEnvironmentSuppliesCredentials() throws IOException {
        rewrite("sources.yaml", "id: sample-newsletter\n    enabled: false", "id: sample-newsletter\n    enabled: true");
        var env = Map.of("IMAP_HOST", "imap.example.org", "IMAP_USER", "someone", "IMAP_PASSWORD", "secret");

        assertThat(ConfigFixtures.loaderFor(configDir, VALIDATOR, env).load().sources().sources())
                .filteredOn("enabled", true)
                .extracting("id")
                .contains("sample-newsletter");
    }

    @Test
    void fallsBackToTheClasspathDefaultWhenTheExternalFileIsAbsent() throws IOException {
        // The two layers are the point: a working default ships in the jar, and the
        // external directory overrides it file by file. Removing one file must fall back,
        // not fail — otherwise every deployment needs a full set of files to say nothing.
        Files.delete(configDir.resolve("sources.yaml"));

        assertThat(ConfigFixtures.loaderFor(configDir, VALIDATOR).load().sources().sources())
                .isNotEmpty();
    }

    @Test
    void theExternalFileWinsOverTheDefault() throws IOException {
        rewrite("matching-rules.yaml", "min_remote_percent: 80", "min_remote_percent: 55");

        assertThat(ConfigFixtures.loaderFor(configDir, VALIDATOR)
                        .load()
                        .rules()
                        .hardFilters()
                        .remote()
                        .minRemotePercent())
                .isEqualTo(55);
    }

    @Test
    void runsOnTheDefaultsAloneWithNoExternalDirectoryAtAll() {
        // What a fresh clone does: no config directory, and the tool still starts.
        Path empty = configDir.resolve("nothing-here");

        assertThat(ConfigFixtures.loaderFor(empty, VALIDATOR).load().rules()).isNotNull();
    }

    @Test
    void reportsAViolationWithItsPath() throws IOException {
        rewrite("matching-rules.yaml", "min_remote_percent: 80", "min_remote_percent: 180");

        assertThatThrownBy(() -> ConfigFixtures.loaderFor(configDir, VALIDATOR).load())
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("hardFilters.remote.minRemotePercent");
    }

    private void rewrite(String file, String from, String to) throws IOException {
        Path path = configDir.resolve(file);
        String content = Files.readString(path);
        assertThat(content).as("fixture must contain %s", from).contains(from);
        Files.writeString(path, content.replace(from, to));
    }
}
