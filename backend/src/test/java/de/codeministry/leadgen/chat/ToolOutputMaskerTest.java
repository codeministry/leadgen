/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.ConfigSnapshot;
import de.codeministry.leadgen.config.Secrets;
import de.codeministry.leadgen.config.model.SourcesConfig;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The one place every tool result passes before the model reads it: the configured mailbox address
 * and every value {@link Secrets} would mask leave as the mask, wherever in the JSON they sit.
 *
 * <p>The end-to-end half — every request body a stub model received during a real turn — is
 * {@code ChatRedactionTest} (T31).
 */
class ToolOutputMaskerTest {

    /** Named in {@code sources.yaml} as the connection's user, and nowhere in the environment. */
    private static final String MAILBOX = "inbox.owner@example.test";

    private static final String MAILBOX_PASSWORD = "imap-pass-8841";
    private static final String API_KEY = "sk-test-4711-secret";

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolOutputMasker masker;

    private ConfigRegistry config;

    private SourcesConfig sources;

    /**
     * Fix 4B-9: the values are collected once per configuration snapshot, not on every tool call —
     * two results under one snapshot read the connections once.
     */
    @Test
    void readsTheConfiguredValuesOncePerSnapshot() {
        masker.mask("{\"note\": \"first\"}");
        masker.mask("{\"note\": \"second\"}");

        verify(sources, times(1)).connections();
    }

    /** Fix 4B-9: a reload that brings a new secret is masked from the next call on. */
    @Test
    void aReloadWithANewSecretIsMaskedFromTheNextCall() {
        assertThat(masker.mask("uses imap-pass-8841")).isEqualTo("uses " + Secrets.MASK);
        var rotated = new SourcesConfig.Connection(
                "mailbox-primary",
                "imap",
                "imap.example.test",
                993,
                true,
                MAILBOX,
                "rotated-pass-9902",
                "poll",
                Duration.ofMinutes(15),
                null);
        SourcesConfig reloaded = mock(SourcesConfig.class);
        when(reloaded.connections()).thenReturn(List.of(rotated));
        ConfigSnapshot next = mock(ConfigSnapshot.class);
        when(next.sources()).thenReturn(reloaded);
        when(config.snapshot()).thenReturn(next);

        assertThat(masker.mask("now uses rotated-pass-9902 at inbox.owner@example.test"))
                .isEqualTo("now uses " + Secrets.MASK + " at " + Secrets.MASK);
    }

    @BeforeEach
    void configuration() {
        var connection = new SourcesConfig.Connection(
                "mailbox-primary",
                "imap",
                "imap.example.test",
                993,
                true,
                MAILBOX,
                MAILBOX_PASSWORD,
                "poll",
                Duration.ofMinutes(15),
                null);
        sources = mock(SourcesConfig.class);
        when(sources.connections()).thenReturn(List.of(connection));
        ConfigSnapshot snapshot = mock(ConfigSnapshot.class);
        when(snapshot.sources()).thenReturn(sources);
        config = mock(ConfigRegistry.class);
        when(config.snapshot()).thenReturn(snapshot);

        var environment = new MockEnvironment()
                .withProperty("LLM_API_KEY", API_KEY)
                .withProperty("OTHER_USER", "second.box@example.test")
                .withProperty("LLM_MODEL_CHAT", "some-model")
                .withProperty("SHORT_TOKEN", "ab");

        masker = new ToolOutputMasker(config, environment);
    }

    /**
     * Fix 5B-4: the database login and the shell's {@code USER} are no secret and no address.
     * Masked as values, {@code postgres} turned every "PostgreSQL" in an advert into "***QL"; the
     * mailbox is still masked, and a secret that is a common word goes only where it is a word.
     */
    @Test
    void anOrdinaryLoginIsNoSecretAndASecretWordNeverEatsALongerOne() {
        var environment = new MockEnvironment()
                .withProperty("spring.datasource.username", "postgres")
                .withProperty("USER", "postgres")
                .withProperty("LLM_API_KEY", "kafka");
        var plain = new ToolOutputMasker(config, environment);

        assertThat(plain.mask("{\"text\":\"PostgreSQL or Postgres, kafkaesque Kafka, write to " + MAILBOX + "\"}"))
                .isEqualTo("{\"text\":\"PostgreSQL or Postgres, kafkaesque " + Secrets.MASK + ", write to "
                        + Secrets.MASK + "\"}");
    }

    @Test
    void masksTheMailboxAndEverySecretInsideNestedJsonStrings() throws Exception {
        String result = """
                {"matched": 1, "offers": [{"id": 7, "archived": true, "title": "Java developer",
                  "text": "Reply to INBOX.Owner@example.test, not to second.box@example.test",
                  "note": {"events": [{"note": "key sk-test-4711-secret and imap-pass-8841"}]}}],
                 "profile": "postgres://leadgen:dbpass-77@db:5432/leadgen"}
                """;

        String masked = masker.mask(result);

        assertThat(masked.toLowerCase())
                .doesNotContain(MAILBOX)
                .doesNotContain("second.box@example.test")
                .doesNotContain(API_KEY)
                .doesNotContain(MAILBOX_PASSWORD)
                .doesNotContain("dbpass-77");
        // Still the same document for the model: structure, numbers, booleans and text around.
        JsonNode tree = JSON.readTree(masked);
        assertThat(tree.path("matched").asInt()).isEqualTo(1);
        assertThat(tree.path("offers").get(0).path("id").asLong()).isEqualTo(7);
        assertThat(tree.path("offers").get(0).path("archived").asBoolean()).isTrue();
        assertThat(tree.path("offers").get(0).path("title").asText()).isEqualTo("Java developer");
        assertThat(tree.path("offers").get(0).path("text").asText())
                .isEqualTo("Reply to " + Secrets.MASK + ", not to " + Secrets.MASK);
    }

    @Test
    void masksAValueTheModelCouldOnlyReadInsideAStringThatIsItselfJson() {
        // A tool that serialised a row with a JSON column hands over JSON inside a string.
        String result = "{\"arguments\": \"{\\\"from\\\": \\\"inbox.owner@example.test\\\"}\"}";

        assertThat(masker.mask(result)).doesNotContain(MAILBOX);
    }

    @Test
    void masksPlainTextThatIsNoJsonAtAll() {
        assertThat(masker.mask("Mailbox inbox.owner@example.test uses imap-pass-8841"))
                .isEqualTo("Mailbox " + Secrets.MASK + " uses " + Secrets.MASK);
    }

    @Test
    void leavesValuesUnderHarmlessNamesAndTooShortToMatchAlone() {
        // A model name is not a secret, and a two-letter secret would blank out every word it
        // occurs in without protecting anything.
        String text = "{\"model\": \"some-model\", \"note\": \"about the lab\"}";

        assertThat(masker.mask(text)).isEqualTo(text);
    }

    @Test
    void nothingIsNothing() {
        assertThat(masker.mask(null)).isNull();
        assertThat(masker.mask("")).isEmpty();
    }
}
