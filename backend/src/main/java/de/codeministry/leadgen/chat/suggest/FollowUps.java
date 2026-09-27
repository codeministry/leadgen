/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.suggest;

import de.codeministry.leadgen.chat.ChatSourceKind;
import de.codeministry.leadgen.chat.ChatTurnState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Two or three questions beneath a finished answer, derived from the tool calls that turn stored
 * (ISC-457).
 *
 * <p><b>Only from the turn's ledger.</b> The ids a follow-up names are read from the turn's own
 * {@code chat_tool_call.returned_ids} — the ids the grounding check holds a citation against —
 * and {@link Phrasing} drops any phrasing whose digits differ, so no follow-up names an id the
 * turn did not see. A stopped, incomplete or still-streaming turn gets none: its tool calls are
 * not a finished answer's.
 *
 * <p>Reads with its own statements rather than through {@code ConversationRepository}, which also
 * writes; this package reads and never writes.
 */
@Service
@RequiredArgsConstructor
public class FollowUps {

    static final int MOST = 3;
    static final int LEAST = 2;

    private static final String STATE = """
            SELECT state FROM chat_turn WHERE id = :turn AND conversation_id = :conversation
            """;

    private static final String CALLS = """
            SELECT c.tool, r.value ->> 'kind' AS kind, (r.value ->> 'id')::bigint AS ref_id
              FROM chat_tool_call c
              LEFT JOIN LATERAL jsonb_array_elements(c.returned_ids) WITH ORDINALITY AS r(value, n) ON true
             WHERE c.turn_id = :turn
             ORDER BY c.ordinal, r.n
            """;

    private final JdbcClient jdbc;
    private final Phrasing phrasing;

    /** One returned id of one tool call, or the call alone when it returned none. */
    private record Call(String tool, String kind, Long id) {}

    /**
     * The follow-ups for a turn of a conversation.
     *
     * @return empty when the conversation holds no such turn; an empty list for any turn not
     * {@code DONE}; otherwise two or three
     */
    public Optional<List<FollowUp>> of(long conversationId, long turnId, String language) {
        Optional<String> state = jdbc.sql(STATE)
                .param("turn", turnId)
                .param("conversation", conversationId)
                .query(String.class)
                .optional();
        if (state.isEmpty()) {
            return Optional.empty();
        }
        if (!ChatTurnState.DONE.name().equals(state.get())) {
            return Optional.of(List.of());
        }
        List<Call> calls = jdbc.sql(CALLS)
                .param("turn", turnId)
                .query((rs, index) ->
                        new Call(rs.getString("tool"), rs.getString("kind"), rs.getObject("ref_id", Long.class)))
                .list();
        Map<String, String> sentences = derive(calls, language);
        Map<String, String> texts = phrasing.phrase("followups " + turnId, sentences, language);
        return Optional.of(sentences.keySet().stream()
                .map(item -> new FollowUp(texts.get(item)))
                .toList());
    }

    /** The catalog's follow-ups for these calls, by item key, in the order they are offered. */
    private static Map<String, String> derive(List<Call> calls, String language) {
        Set<String> tools = new LinkedHashSet<>();
        Set<Long> offerIds = new LinkedHashSet<>();
        Set<Long> applicationIds = new LinkedHashSet<>();
        for (Call call : calls) {
            tools.add(call.tool());
            if (call.id() == null) {
                continue;
            }
            if (ChatSourceKind.OFFER.name().equals(call.kind())) {
                offerIds.add(call.id());
            } else if (ChatSourceKind.APPLICATION.name().equals(call.kind())) {
                applicationIds.add(call.id());
            }
        }
        List<Long> offers = new ArrayList<>(offerIds);
        Map<String, String> items = new LinkedHashMap<>();
        if (offers.size() >= 2) {
            items.put("offers", sentence(language, "compare", offers.get(0), offers.get(1)));
        } else if (offers.size() == 1) {
            items.put("offers", sentence(language, "offer", offers.getFirst(), null));
        }
        if (!applicationIds.isEmpty()) {
            items.put(
                    "application",
                    sentence(language, "application", applicationIds.iterator().next(), null));
        }
        if (tools.contains("statistics")) {
            items.put("statistics", sentence(language, "statistics", null, null));
        }
        if (!offers.isEmpty()) {
            items.put("fit", sentence(language, "fit", offers.getFirst(), null));
        }
        if (tools.contains("profile")) {
            items.put("profile", sentence(language, "profile", null, null));
        }
        for (String filler : List.of("shortlist", "open")) {
            if (items.size() < LEAST) {
                items.putIfAbsent(filler, sentence(language, filler, null, null));
            }
        }
        Map<String, String> offered = new LinkedHashMap<>();
        items.entrySet().stream().limit(MOST).forEach(entry -> offered.put(entry.getKey(), entry.getValue()));
        return offered;
    }

    private static String sentence(String language, String key, Long a, Long b) {
        Map<String, String> params = new LinkedHashMap<>();
        if (a != null) {
            params.put("a", a.toString());
        }
        if (b != null) {
            params.put("b", b.toString());
        }
        return Catalog.sentence(language, "chat.followup." + key, params);
    }
}
