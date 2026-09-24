/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.codeministry.leadgen.config.model.CoverLetterStyle;
import de.codeministry.leadgen.config.model.PipelineConfig;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

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
    void rejectsBatchingOnAProviderThatHasNoBatchEndpoint() throws IOException {
        // The same class of lie as an unimplemented auth mode: the flag would be read,
        // ignored, and the run would score synchronously at full price while the person who
        // set it believes they are paying half.
        rewrite("pipeline.yaml", "batch: ${LLM_BATCH:false}", "batch: true");
        rewrite("pipeline.yaml", "provider: ${LLM_PROVIDER:}", "provider: ollama");

        assertThatThrownBy(() -> ConfigFixtures.loaderFor(configDir, VALIDATOR).load())
                .hasMessageContaining("llm.batch")
                .hasMessageContaining("ollama");
    }

    @Test
    void readsTheModelTimeoutAndFallsBackWhenTheKeyIsAbsent() throws IOException {
        // The shipped file names PT120S, because 30 s was a ceiling a local model loading a
        // 20B file cannot meet and no configuration could raise.
        assertThat(ConfigFixtures.loaderFor(configDir, VALIDATOR)
                        .load()
                        .application()
                        .llm()
                        .timeout())
                .isEqualTo(Duration.ofSeconds(120));

        // A configuration written before the key existed is not a broken one.
        rewrite("pipeline.yaml", "  timeout: ${LLM_TIMEOUT:PT120S}\n", "");

        assertThat(ConfigFixtures.loaderFor(configDir, VALIDATOR)
                        .load()
                        .application()
                        .llm()
                        .timeout())
                .isEqualTo(PipelineConfig.Llm.DEFAULT_TIMEOUT);
    }

    @Test
    void acceptsBatchingOnTheProviderThatHasOne() throws IOException {
        rewrite("pipeline.yaml", "batch: ${LLM_BATCH:false}", "batch: true");
        rewrite("pipeline.yaml", "provider: ${LLM_PROVIDER:}", "provider: anthropic");

        assertThat(ConfigFixtures.loaderFor(configDir, VALIDATOR)
                        .load()
                        .application()
                        .llm()
                        .batch())
                .isTrue();
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
        rewrite(
                "sources.yaml",
                "id: sample-newsletter\n    enabled: false",
                "id: sample-newsletter\n    enabled: true");

        assertThatThrownBy(() -> ConfigFixtures.loaderFor(configDir, VALIDATOR).load())
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("IMAP_HOST");
    }

    @Test
    void acceptsAnEnabledMailSourceOnceTheEnvironmentSuppliesCredentials() throws IOException {
        rewrite(
                "sources.yaml",
                "id: sample-newsletter\n    enabled: false",
                "id: sample-newsletter\n    enabled: true");
        var env = Map.of("IMAP_HOST", "imap.example.org", "IMAP_USER", "someone", "IMAP_PASSWORD", "secret");

        assertThat(ConfigFixtures.loaderFor(configDir, VALIDATOR, env)
                        .load()
                        .sources()
                        .sources())
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

        assertThat(ConfigFixtures.loaderFor(configDir, VALIDATOR)
                        .load()
                        .sources()
                        .sources())
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
    void rejectsOidcWithNoIssuerToVerifyAgainst() throws IOException {
        // The mode without the issuer is the shape that looks configured and protects
        // nothing: a resource server would be assembled with nowhere to fetch keys from.
        rewrite("pipeline.yaml", "auth: ${AUTH_MODE:none}", "auth: oidc");

        assertThatThrownBy(() -> ConfigFixtures.loaderFor(configDir, VALIDATOR).load())
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("security.oidc.issuer is empty");
    }

    @Test
    void stillRejectsAModeNobodyImplemented() throws IOException {
        rewrite("pipeline.yaml", "auth: ${AUTH_MODE:none}", "auth: basic");

        assertThatThrownBy(() -> ConfigFixtures.loaderFor(configDir, VALIDATOR).load())
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("implemented are 'none' and 'oidc'");
    }

    @Test
    void rejectsDedicatedModeBesideASenderFilter() throws IOException {
        // The two say opposite things and the sender wins, because `from` is in the IMAP
        // search term and no flag in the selector takes it out again.
        rewrite("sources.yaml", "match_all: false", "match_all: true");

        assertThatThrownBy(() -> ConfigFixtures.loaderFor(configDir, VALIDATOR).load())
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("name one or the other");
    }

    @Test
    void rejectsASelectorThatNamesNoFilterAndDoesNotSaySoOutLoud() throws IOException {
        // Reading the whole folder is a legitimate thing to want and an accident that looks
        // identical to it. `match_all: true` is how the intention gets written down.
        rewrite("sources.yaml", """
                      from: [ "newsletter@example.com" ]
                      subject_matches: ".*new projects.*"
                """, "");

        assertThatThrownBy(() -> ConfigFixtures.loaderFor(configDir, VALIDATOR).load())
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("would read every message in 'INBOX'");
    }

    @Test
    void rejectsADedicatedSourceInAFolderAnotherEnabledSourceReads() throws IOException {
        // The loss this one prevents is the silent kind: the dedicated source flags the
        // other's mail as taken before that source has run, and the run reports zero.
        rewrite(
                "sources.yaml",
                "id: sample-newsletter\n    enabled: false",
                "id: sample-newsletter\n    enabled: true");
        rewrite(
                "sources.yaml",
                "id: sample-direct-enquiry\n    enabled: false",
                "id: sample-direct-enquiry\n    enabled: true");
        rewrite("sources.yaml", "subject_matches: \"(?i).*(enquiry|availability|opportunity).*\"", "match_all: true");

        assertThatThrownBy(() -> ConfigFixtures.loaderFor(configDir, VALIDATOR).load())
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("give one of them a folder of its own");
    }

    @Test
    void reportsAViolationWithItsPath() throws IOException {
        rewrite("matching-rules.yaml", "min_remote_percent: 80", "min_remote_percent: 180");

        assertThatThrownBy(() -> ConfigFixtures.loaderFor(configDir, VALIDATOR).load())
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("hardFilters.remote.minRemotePercent");
    }

    @Test
    void bindsBothTopicListsAndTriesANameWithoutAliasesAsItsOwnSpelling() throws IOException {
        rewrite(
                "skill-profile.yaml",
                "  - { name: Example unwanted topic, weight: 6, aliases: [ Unwanted field ] }",
                "  - { name: Example unwanted topic, weight: 6 }");

        var profile = ConfigFixtures.loaderFor(configDir, VALIDATOR).load().profile();

        assertThat(profile.interestTopicsOrEmpty()).singleElement().satisfies(topic -> {
            assertThat(topic.weight()).isEqualTo(8);
            assertThat(topic.spellings()).containsExactly("Example topic", "Example field", "Beispielthema");
        });
        assertThat(profile.disinterestTopicsOrEmpty())
                .singleElement()
                .satisfies(topic -> assertThat(topic.spellings()).containsExactly("Example unwanted topic"));
    }

    @Test
    void refusesTheRetiredAntiSkillsKeyByNamingWhereItWent() throws IOException {
        // A configuration that loaded yesterday. Jackson alone would say "Unrecognized
        // field", which names the key and not where its entries belong now.
        rewrite("matching-rules.yaml", "\ndeduplication:", "\nanti_skills:\n  - .NET\n\ndeduplication:");

        assertThatThrownBy(() -> ConfigFixtures.loaderFor(configDir, VALIDATOR).load())
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("disinterest_topics")
                .hasMessageContaining("skill-profile.yaml")
                .hasMessageNotContaining("Unrecognized field");
    }

    @Test
    void refusesTheRetiredSelectorKeysByNamingWhatReplacedThem() throws IOException {
        for (String retired : new String[] {"state: uid", "mark_seen: false"}) {
            materializeTheShippedExamples();
            rewrite("sources.yaml", "      match_all: false\n", "      match_all: false\n      " + retired + "\n");

            assertThatThrownBy(
                            () -> ConfigFixtures.loaderFor(configDir, VALIDATOR).load())
                    .as(retired)
                    .isInstanceOf(ConfigValidationException.class)
                    .hasMessageContaining("progress_flag")
                    .hasMessageNotContaining("Unrecognized field");
        }
    }

    @Test
    void readsTheProgressFlagAndRefusesOneThatIsNoImapKeyword() throws IOException {
        var env = Map.of("IMAP_PROGRESS_FLAG", "leadgen-dev");
        var connection = ConfigFixtures.loaderFor(configDir, VALIDATOR, env)
                .load()
                .sources()
                .connections()
                .getFirst();
        assertThat(connection.progressFlagOrDefault()).isEqualTo("leadgen-dev");

        // Unset, it is what every instance wrote before the key existed.
        assertThat(ConfigFixtures.loaderFor(configDir, VALIDATOR)
                        .load()
                        .sources()
                        .connections()
                        .getFirst()
                        .progressFlagOrDefault())
                .isEqualTo("leadgen");

        // A space or a parenthesis would end the keyword inside the IMAP SEARCH itself.
        var broken = Map.of("IMAP_PROGRESS_FLAG", "leadgen dev");
        assertThatThrownBy(() ->
                        ConfigFixtures.loaderFor(configDir, VALIDATOR, broken).load())
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("progressFlag");
    }

    @Test
    void refusesATopicThatIsBothWantedAndUnwanted() throws IOException {
        rewrite("skill-profile.yaml", "name: Example unwanted topic", "name: example TOPIC");

        assertThatThrownBy(() -> ConfigFixtures.loaderFor(configDir, VALIDATOR).load())
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("topic 'example TOPIC' is in both");
    }

    @Test
    void acceptsATopicSpelledLikeASkillAndSaysSo() throws IOException {
        rewrite("skill-profile.yaml", "aliases: [ Example field, Beispielthema ]", "aliases: [ Postgres ]");

        ListAppender<ILoggingEvent> log = new ListAppender<>();
        Logger loaderLog = (Logger) LoggerFactory.getLogger(ConfigLoader.class);
        log.start();
        loaderLog.addAppender(log);
        try {
            assertThat(ConfigFixtures.loaderFor(configDir, VALIDATOR)
                            .load()
                            .profile()
                            .interestTopicsOrEmpty())
                    .hasSize(1);
        } finally {
            loaderLog.detachAppender(log);
        }

        assertThat(log.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .filteredOn(message -> message.contains("move the score twice"))
                .singleElement()
                .satisfies(
                        message -> assertThat(message).contains("Example topic").contains("PostgreSQL"));
    }

    @Test
    void theCoverLetterStyleShipsRulesAndNoExampleLetter() {
        // The fixture materialises the four older files only, so this one comes from the jar.
        assertThat(configDir.resolve(ConfigLoader.STYLE_FILE)).doesNotExist();

        ListAppender<ILoggingEvent> log = new ListAppender<>();
        Logger loaderLog = (Logger) LoggerFactory.getLogger(ConfigLoader.class);
        log.start();
        loaderLog.addAppender(log);
        CoverLetterStyle style;
        try {
            style = ConfigFixtures.loaderFor(configDir, VALIDATOR).load().coverLetter();
        } finally {
            loaderLog.detachAppender(log);
        }

        assertThat(style.forLanguage("de").neutralSalutation()).isEqualTo("Sehr geehrte Damen und Herren,");
        assertThat(style.forLanguage("de").closing()).isEqualTo("Mit freundlichen Grüßen");
        assertThat(style.forLanguage("en").neutralSalutation()).isEqualTo("Dear Sir or Madam,");
        assertThat(style.forLanguage("en").closing()).isEqualTo("Kind regards");
        for (String language : List.of("de", "en")) {
            var rules = style.forLanguage(language);
            assertThat(rules.bannedPhrases())
                    .as("banned phrases for %s", language)
                    .isNotEmpty();
            assertThat(rules.wordLimit()).as("word limit for %s", language).isPositive();
            // The letter's fixed lines are content, so they ship here and not in the code.
            assertThat(rules.neutralSalutation())
                    .as("neutral salutation for %s", language)
                    .isNotBlank();
            assertThat(rules.namedSalutation())
                    .as("named salutation for %s", language)
                    .contains("{name}");
            assertThat(rules.closing()).as("closing for %s", language).isNotBlank();
            assertThat(style.examplesFor(language))
                    .as("a committed example letter would be a personal datum")
                    .isEmpty();
        }
        // Not only the two named languages: no key of the shipped file may carry a letter.
        assertThat(style.examples().values())
                .allSatisfy(letters -> assertThat(letters).isEmpty());
        assertThat(log.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .contains("cover-letter.yaml read from classpath:/leadgen/cover-letter.yaml")
                .noneMatch(message -> message.contains("names no rules for"));
    }

    @Test
    void anOverrideInTheConfigDirectoryWinsForTheCoverLetterStyle() throws IOException {
        Files.writeString(configDir.resolve(ConfigLoader.STYLE_FILE), """
                version: 1
                rules:
                  de:
                    banned_phrases: [ "mit großer Freude" ]
                    word_limit: 150
                    neutral_salutation: "Guten Tag,"
                    named_salutation: "Hallo {name},"
                    closing: "Beste Grüße"
                examples:
                  de:
                    - "An example letter, used for tone only."
                """);

        ListAppender<ILoggingEvent> log = new ListAppender<>();
        Logger loaderLog = (Logger) LoggerFactory.getLogger(ConfigLoader.class);
        log.start();
        loaderLog.addAppender(log);
        CoverLetterStyle style;
        try {
            style = ConfigFixtures.loaderFor(configDir, VALIDATOR).load().coverLetter();
        } finally {
            loaderLog.detachAppender(log);
        }

        // English letters are now unguarded on phrases and length, and the log has to say so.
        assertThat(log.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("names no rules for 'en'"))
                .noneMatch(message -> message.contains("names no rules for 'de'"));
        assertThat(style.forLanguage("DE").bannedPhrases()).containsExactly("mit großer Freude");
        assertThat(style.forLanguage("de").wordLimit()).isEqualTo(150);
        assertThat(style.forLanguage("de").structureNotes()).isEmpty();
        assertThat(style.forLanguage("de").namedSalutation()).isEqualTo("Hallo {name},");
        assertThat(style.forLanguage("de").closing()).isEqualTo("Beste Grüße");
        assertThat(style.examplesFor("de")).hasSize(1);
        // The layers override file by file, never key by key: English is simply absent now.
        assertThat(style.forLanguage("en").bannedPhrases()).isEmpty();
        assertThat(style.examplesFor("en")).isEmpty();
    }

    @Test
    void rejectsACoverLetterWordLimitBelowOne() throws IOException {
        Files.writeString(configDir.resolve(ConfigLoader.STYLE_FILE), """
                version: 1
                rules:
                  en:
                    word_limit: 0
                    neutral_salutation: "Dear Sir or Madam,"
                    named_salutation: "Dear {name},"
                    closing: "Kind regards"
                """);

        assertThatThrownBy(() -> ConfigFixtures.loaderFor(configDir, VALIDATOR).load())
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("wordLimit");
    }

    @Test
    void rejectsACoverLetterLanguageWithoutItsLetterLines() throws IOException {
        // A drafted letter is assembled from these three lines; a language without them has
        // no greeting and no closing anybody chose, and the code has none of its own.
        Files.writeString(configDir.resolve(ConfigLoader.STYLE_FILE), """
                version: 1
                rules:
                  en:
                    word_limit: 200
                """);

        assertThatThrownBy(() -> ConfigFixtures.loaderFor(configDir, VALIDATOR).load())
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("neutralSalutation")
                .hasMessageContaining("namedSalutation")
                .hasMessageContaining("closing");
    }

    @Test
    void rejectsANamedSalutationWithoutTheNamePlaceholder() throws IOException {
        // Without {name} the greeting silently drops the person the advert named.
        Files.writeString(configDir.resolve(ConfigLoader.STYLE_FILE), """
                version: 1
                rules:
                  de:
                    word_limit: 200
                    neutral_salutation: "Sehr geehrte Damen und Herren,"
                    named_salutation: "Guten Tag,"
                    closing: "Mit freundlichen Grüßen"
                """);

        assertThatThrownBy(() -> ConfigFixtures.loaderFor(configDir, VALIDATOR).load())
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("namedSalutation");
    }

    private void rewrite(String file, String from, String to) throws IOException {
        Path path = configDir.resolve(file);
        String content = Files.readString(path);
        assertThat(content).as("fixture must contain %s", from).contains(from);
        Files.writeString(path, content.replace(from, to));
    }
}
