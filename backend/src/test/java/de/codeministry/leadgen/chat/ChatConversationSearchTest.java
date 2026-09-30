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

import de.codeministry.leadgen.Databases;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ISC-450, the server half: {@code GET /conversations?q=} narrows the list to the conversations
 * whose title or any question holds every word typed, ignoring case and accents, newest first.
 *
 * <p>The questions are stored through {@link ConversationRepository#startTurn} — the path every new
 * turn takes — so the search text is kept up by the same write a real turn makes, without a model.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ChatConversationSearchTest {

    private static final ModelStub MODEL = ModelStub.start();

    private static final Path CONFIG = MODEL.configuration();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG::toString);
    }

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConversationRepository conversations;

    private long cafe;
    private long koeln;
    private long kotlin;

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    /** Oldest first, so {@code kotlin} is the newest activity and {@code cafe} the oldest. */
    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM chat_conversation");
        cafe = conversations.create(null);
        conversations.startTurn(cafe, "Welche Java-Rollen passen?", "stub");
        assertThat(mvc.patch()
                        .uri("/api/v1/chat/conversations/{id}", cafe)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Café MÜLLER Bewerbung\"}"))
                .hasStatusOk();
        koeln = conversations.create(null);
        conversations.startTurn(koeln, "Ist die ÉCOLE-Anfrage in Köln REMOTE?", "stub");
        kotlin = conversations.create(null);
        conversations.startTurn(kotlin, "Kotlin gesucht", "stub");
        conversations.startTurn(kotlin, "Auch remote möglich?", "stub");
    }

    @Test
    void oneWordMatchesTitleOrAnyQuestionIgnoringCase() {
        assertThat(search("JAVA")).containsExactly(cafe);
        assertThat(search("müller")).containsExactly(cafe);
        assertThat(search("möglich")).containsExactly(kotlin);
    }

    @Test
    void twoWordsMatchOnlyWhereEveryWordIsFound() {
        assertThat(search("cafe bewerbung")).containsExactly(cafe);
        assertThat(search("remote koln")).containsExactly(koeln);
        assertThat(search("cafe kotlin")).isEmpty();
    }

    @Test
    void anAccentFreeSpellingMatchesTheAccentedOne() {
        assertThat(search("Koln")).containsExactly(koeln);
        assertThat(search("ecole")).containsExactly(koeln);
        assertThat(search("muller")).containsExactly(cafe);
        assertThat(search("moglich")).containsExactly(kotlin);
    }

    @Test
    void matchesComeNewestActivityFirst() {
        assertThat(search("remote")).containsExactly(kotlin, koeln);
    }

    @Test
    void aWordInNoConversationFindsNothingAndWildcardsAreLiteral() {
        assertThat(search("zeppelin")).isEmpty();
        assertThat(search("%")).isEmpty();
        assertThat(search("_")).isEmpty();
    }

    @Test
    void noQueryIsTheWholeList() {
        assertThat(ids(mvc.get().uri("/api/v1/chat/conversations"))).containsExactly(kotlin, koeln, cafe);
    }

    private List<Long> search(String q) {
        return ids(mvc.get().uri("/api/v1/chat/conversations").param("q", q));
    }

    private List<Long> ids(MockMvcTester.MockMvcRequestBuilder request) {
        var result = request.exchange();
        assertThat(result).hasStatusOk();
        List<Number> ids = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsByteArray().length == 0
                        ? "[]"
                        : new String(
                                result.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8),
                "$[*].id");
        return ids.stream().map(Number::longValue).toList();
    }
}
