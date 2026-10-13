/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.suggest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code chat_suggestion_phrasing}: the phrasings {@link Phrasing} keeps beyond the process
 * (ISC-531), so a restart does not cost the next open a model call.
 *
 * <p>The row is found by a SHA-256 of {@link Phrasing}'s key, which holds the catalog sentences
 * and is too long to index as it is. Two instances phrasing one key keep the first row. The table
 * keeps the newest {@link #KEPT} rows, the same bound the map in front of it has.
 */
@Component
@RequiredArgsConstructor
class PhrasingStore {

    static final int KEPT = 256;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final TypeReference<Map<String, String>> SENTENCES = new TypeReference<>() {};

    private final JdbcClient jdbc;

    Optional<Map<String, String>> find(String key) {
        return jdbc.sql("SELECT phrased::text FROM chat_suggestion_phrasing WHERE cache_key = ?")
                .param(hash(key))
                .query(String.class)
                .optional()
                .map(PhrasingStore::read);
    }

    void save(
            String key,
            String snapshot,
            String language,
            String model,
            Map<String, String> sentences,
            Map<String, String> phrased) {
        jdbc.sql("""
                INSERT INTO chat_suggestion_phrasing (cache_key, snapshot, language, model, sentences, phrased)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)
                ON CONFLICT (cache_key) DO NOTHING
                """)
                .params(hash(key), snapshot, language, model, write(sentences), write(phrased))
                .update();
        jdbc.sql("""
                DELETE FROM chat_suggestion_phrasing
                 WHERE cache_key IN (SELECT cache_key FROM chat_suggestion_phrasing
                                      ORDER BY created_at DESC, cache_key OFFSET ?)
                """).param(KEPT).update();
    }

    /** The language of the newest row: what the warm-up uses before the first open since start. */
    Optional<String> newestLanguage() {
        return jdbc.sql("SELECT language FROM chat_suggestion_phrasing ORDER BY created_at DESC LIMIT 1")
                .query(String.class)
                .optional();
    }

    static String hash(String key) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }

    private static String write(Map<String, String> sentences) {
        try {
            return JSON.writeValueAsString(sentences);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> read(String json) {
        try {
            return Map.copyOf(JSON.readValue(json, SENTENCES));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a stored phrasing is not a JSON object: " + json, e);
        }
    }
}
