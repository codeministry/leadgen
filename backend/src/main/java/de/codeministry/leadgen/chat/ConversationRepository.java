/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three chat tables, read and written with plain SQL like the rest of the read side.
 *
 * <p>Nothing in the pipeline reads these tables, and nothing here reads a pipeline table except
 * to follow a pin. That separation is what lets the chat be absent without the tool noticing.
 */
@Repository
@RequiredArgsConstructor
public class ConversationRepository {

    private static final String LIST = """
            SELECT id, title, updated_at
              FROM chat_conversation
             ORDER BY updated_at DESC, id DESC
            """;

    private static final String CONVERSATION = """
            SELECT id, title, pinned_offer_id, updated_at
              FROM chat_conversation
             WHERE id = :id
            """;

    private static final String TURNS = """
            SELECT id, question, answer_md, state, replaces_turn_id, model, created_at, citations
              FROM chat_turn
             WHERE conversation_id = :id
             ORDER BY ordinal
            """;

    private static final String STEPS = """
            SELECT c.turn_id, c.ordinal, c.tool, c.label, c.duration_ms,
                   jsonb_array_length(c.returned_ids) AS returned
              FROM chat_tool_call c
              JOIN chat_turn t ON t.id = c.turn_id
             WHERE t.conversation_id = :id
             ORDER BY c.turn_id, c.ordinal
            """;

    private static final String START_TURN = """
            INSERT INTO chat_turn (conversation_id, ordinal, question, model, replaces_turn_id)
            VALUES (:conversation,
                    (SELECT coalesce(max(ordinal), 0) + 1 FROM chat_turn WHERE conversation_id = :conversation),
                    :question, :model, CAST(:replaces AS bigint))
            RETURNING id
            """;

    private static final String TOUCH = """
            UPDATE chat_conversation
               SET title = CASE WHEN title = '' THEN :title ELSE title END,
                   updated_at = now()
             WHERE id = :conversation
            """;

    /**
     * The dialogue before a turn. A regenerated turn stands where the question it re-asks was first
     * asked — the root of its chain of replacements — so the model is not shown the turns that came
     * after that question as if they had been said before it.
     */
    private static final String HISTORY = """
            WITH RECURSIVE place AS (
                -- A turn that replaces none stands at its own ordinal; a regenerated one stands
                -- where the first turn of its chain stood, however many regenerations ago.
                SELECT id, ordinal AS position FROM chat_turn
                 WHERE conversation_id = :conversation AND replaces_turn_id IS NULL
                UNION ALL
                SELECT t.id, p.position
                  FROM chat_turn t JOIN place p ON t.replaces_turn_id = p.id
            ),
            later AS (
                -- Every attempt that asked a turn's question again, however far down its chain.
                SELECT r.replaces_turn_id AS replaced, r.id, r.answer_md FROM chat_turn r
                 WHERE r.conversation_id = :conversation AND r.replaces_turn_id IS NOT NULL
                UNION ALL
                SELECT l.replaced, r.id, r.answer_md
                  FROM chat_turn r JOIN later l ON r.replaces_turn_id = l.id
            )
            SELECT t.question, t.answer_md
              FROM chat_turn t
              JOIN place p ON p.id = t.id
             WHERE t.conversation_id = :conversation
               AND p.position < (SELECT position FROM place WHERE id = :turn)
               AND t.answer_md <> ''
               -- A replaced answer is kept for the reader, not for the model: asked again, the
               -- model should not read its own earlier attempt as settled dialogue. Replaced only
               -- by an attempt that answered, though: one cut off before it said anything left no
               -- answer to stand in its place, and dropping the original lost the exchange.
               AND t.id NOT IN (SELECT replaced FROM later WHERE answer_md <> '')
             ORDER BY p.position, t.ordinal
            """;

    private static final String OFFER_SOURCES = """
            SELECT o.id, o.title, s.name AS source_name, o.ingested_at AS at,
                   o.archived_at IS NOT NULL AS archived
              FROM offer o
              JOIN source s ON s.id = o.source_id
             WHERE o.id IN (:ids)
            """;

    private static final String APPLICATION_SOURCES = """
            SELECT a.id, o.title, s.name AS source_name, a.created_at AS at,
                   o.archived_at IS NOT NULL AS archived
              FROM application a
              JOIN offer o ON o.id = a.offer_id
              JOIN source s ON s.id = o.source_id
             WHERE a.id IN (:ids)
            """;

    /** Long enough to tell two conversations apart in the list, short enough for one line of it. */
    static final int TITLE_LENGTH = 80;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final TypeReference<List<TurnLedger.Citation>> CITATIONS = new TypeReference<>() {};

    private final JdbcClient jdbc;

    /**
     * @param pinnedOfferId the offer "Ask about this offer" started it from, or null.
     * @return the new conversation's id. Its title stays empty until the first question names it.
     */
    public long create(Long pinnedOfferId) {
        return jdbc.sql("INSERT INTO chat_conversation (title, pinned_offer_id) VALUES ('', :pin) RETURNING id")
                .param("pin", pinnedOfferId)
                .query(Long.class)
                .single();
    }

    /** Newest first: the conversation whose last turn started most recently leads. */
    public List<ConversationSummary> list() {
        return jdbc.sql(LIST)
                .query((rs, index) -> new ConversationSummary(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getTimestamp("updated_at").toInstant()))
                .list();
    }

    /**
     * The whole conversation with its turns and the tool calls each made.
     *
     * <p>Sources come back empty here; resolving what the calls returned into rows the screen can
     * link is the turn's business, not the list's.
     */
    @Transactional(readOnly = true)
    public Optional<ConversationView> find(long id) {
        Optional<Header> header = jdbc.sql(CONVERSATION)
                .param("id", id)
                .query((rs, index) -> new Header(
                        rs.getLong("id"),
                        rs.getString("title"),
                        (Long) rs.getObject("pinned_offer_id"),
                        rs.getTimestamp("updated_at").toInstant()))
                .optional();
        if (header.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, List<ChatStep>> steps = steps(id);
        List<StoredTurn> stored = jdbc.sql(TURNS)
                .param("id", id)
                .query(ConversationRepository::stored)
                .list();
        // One lookup for every turn's citations together — two queries, not two per turn.
        Map<TurnLedger.Ref, Row> rows =
                rows(stored.stream().flatMap(turn -> turn.citations().stream()).toList());
        List<TurnView> turns = stored.stream()
                .map(turn -> turn.view(steps.getOrDefault(turn.id(), List.of()), sources(turn.citations(), rows)))
                .toList();
        Header h = header.get();
        return Optional.of(new ConversationView(h.id(), h.title(), h.pinnedOfferId(), turns, h.updatedAt()));
    }

    /** Whether a conversation of this id exists, asked before a turn is started on it. */
    public boolean exists(long id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM chat_conversation WHERE id = :id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    /**
     * Stores a new turn as streaming, names the conversation after its first question and moves
     * it to the top of the list.
     *
     * @return the turn's id
     */
    @Transactional
    public long startTurn(long conversationId, String question, String model) {
        return startTurn(conversationId, question, model, null);
    }

    /**
     * @param replaces the turn this one answers again, set by regenerate; null for a new question.
     *
     * <p>The conversation is touched <em>before</em> the turn is inserted, and that order is the
     * point: the {@code UPDATE} takes the conversation's row lock, so a second turn started on the
     * same conversation at the same moment — two tabs, a double click — waits for this transaction
     * and then reads the ordinal it committed. Inserted first, both would read the same {@code
     * max(ordinal)} and the second would fail on {@code UNIQUE (conversation_id, ordinal)}.
     */
    @Transactional
    public long startTurn(long conversationId, String question, String model, Long replaces) {
        jdbc.sql(TOUCH)
                .param("conversation", conversationId)
                .param("title", title(question))
                .update();
        return jdbc.sql(START_TURN)
                .param("conversation", conversationId)
                .param("question", question)
                .param("model", model)
                .param("replaces", replaces)
                .query(Long.class)
                .single();
    }

    /** The offer the conversation was started from, if it was and the offer still exists. */
    public Optional<Long> pinnedOffer(long conversationId) {
        return jdbc.sql("SELECT pinned_offer_id FROM chat_conversation WHERE id = :id AND pinned_offer_id IS NOT NULL")
                .param("id", conversationId)
                .query(Long.class)
                .optional();
    }

    /**
     * Ends every turn still at {@code STREAMING} as {@code INCOMPLETE}, keeping its text; see
     * {@link ChatTurnService#sweep} for when that is safe.
     *
     * @return how many turns it ended
     */
    public int abandonStreaming(OffsetDateTime startedBefore) {
        return jdbc.sql("""
                        UPDATE chat_turn SET state = 'INCOMPLETE', finished_at = now()
                         WHERE state = 'STREAMING' AND created_at < :startedBefore
                        """).param("startedBefore", startedBefore).update();
    }

    /**
     * The database's clock now. {@code created_at} is written by that clock, so a cut-off compared
     * with it is read off the same one; the application's clock may disagree by more than a turn.
     */
    public OffsetDateTime now() {
        return jdbc.sql("SELECT now()").query(OffsetDateTime.class).single();
    }

    /** The question a turn of this conversation asked; empty for a turn of another one. */
    public Optional<String> question(long conversationId, long turnId) {
        return jdbc.sql("SELECT question FROM chat_turn WHERE id = :turn AND conversation_id = :conversation")
                .param("turn", turnId)
                .param("conversation", conversationId)
                .query(String.class)
                .optional();
    }

    /**
     * The conversation's earlier turns that got an answer, oldest first, as the model's history;
     * for a regenerated turn, the ones before the question it asks again.
     */
    public List<PastTurn> history(long conversationId, long beforeTurnId) {
        return jdbc.sql(HISTORY)
                .param("conversation", conversationId)
                .param("turn", beforeTurnId)
                .query((rs, index) -> new PastTurn(rs.getString("question"), rs.getString("answer_md")))
                .list();
    }

    /**
     * Appends streamed text to a turn's answer, so a turn that ends any way at all — finished,
     * broken off, the process gone — keeps what the reader already saw. The turn buffers its
     * chunks and calls this a few times a second ({@link ChatTurnService#FLUSH_INTERVAL}), not
     * once per token.
     */
    public void append(long turnId, String delta) {
        jdbc.sql("UPDATE chat_turn SET answer_md = answer_md || :delta WHERE id = :id")
                .param("delta", delta)
                .param("id", turnId)
                .update();
    }

    /**
     * The fallback when {@link #finish} was rejected: the state alone, in a transaction of its own,
     * so a tool-call row the table refuses cannot leave the turn {@code STREAMING} until a restart.
     * The tool calls and citations of such a turn are lost; its text was written as it streamed.
     *
     * @return false when the row was not streaming any more, or is gone
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean incomplete(long turnId) {
        return jdbc.sql("""
                        UPDATE chat_turn SET state = 'INCOMPLETE', finished_at = now()
                         WHERE id = :id AND state = 'STREAMING'
                        """).param("id", turnId).update() > 0;
    }

    /**
     * Ends a turn: its state, the tool calls it made with the ids they returned, and its citations.
     * One transaction, so a rejected row takes the whole write with it; {@link #incomplete} is the
     * fallback that ends the row by its state alone.
     *
     * <p>Labels and arguments carry what the model sent, and a NUL in it (U+0000, escaped in JSON)
     * is refused by both {@code TEXT} and {@code JSONB}. It is dropped here, before the insert.
     */
    @Transactional
    public void finish(
            long turnId, ChatTurnState state, List<TurnLedger.Call> calls, List<TurnLedger.Citation> citations) {
        jdbc.sql("""
                        UPDATE chat_turn
                           SET state = :state, finished_at = now(), citations = CAST(:citations AS jsonb)
                         WHERE id = :id
                        """)
                .param("state", state.name())
                .param("citations", json(citations))
                .param("id", turnId)
                .update();
        for (TurnLedger.Call call : calls) {
            jdbc.sql("""
                            INSERT INTO chat_tool_call (turn_id, ordinal, tool, label, arguments, returned_ids, duration_ms)
                            VALUES (:turn, :ordinal, :tool, :label, CAST(:arguments AS jsonb),
                                    CAST(:returned AS jsonb), :duration)
                            """)
                    .param("turn", turnId)
                    .param("ordinal", call.ordinal())
                    .param("tool", withoutNul(call.tool()))
                    .param("label", withoutNul(call.label()))
                    .param("arguments", arguments(call.arguments()))
                    .param("returned", json(call.returned()))
                    .param("duration", (int) Math.min(Integer.MAX_VALUE, call.durationMs()))
                    .update();
        }
    }

    /**
     * Whether a turn's row still exists. False once its conversation was deleted, which cascades
     * to the turn — the one way a running turn's row disappears under it.
     */
    public boolean turnExists(long turnId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM chat_turn WHERE id = :id)")
                .param("id", turnId)
                .query(Boolean.class)
                .single();
    }

    /**
     * Whether a cited row may become a link: an offer in the working set or the archive — passed
     * and not an attached duplicate — or an application that exists.
     */
    public boolean reachable(ChatSourceKind kind, long id) {
        String sql = kind == ChatSourceKind.OFFER
                ? "SELECT EXISTS (SELECT 1 FROM offer WHERE id = :id AND status = 'PASSED' AND duplicate_of_id IS NULL)"
                : "SELECT EXISTS (SELECT 1 FROM application WHERE id = :id)";
        return jdbc.sql(sql).param("id", id).query(Boolean.class).single();
    }

    /**
     * The citations joined with the rows they name, in citation order. A row deleted since the
     * turn drops out of the list rather than showing as an empty card.
     */
    public List<ChatSource> sources(List<TurnLedger.Citation> citations) {
        return citations.isEmpty() ? List.of() : sources(citations, rows(citations));
    }

    /** The rows the citations name, at most one query per kind however many turns they come from. */
    private Map<TurnLedger.Ref, Row> rows(List<TurnLedger.Citation> citations) {
        Map<TurnLedger.Ref, Row> rows = new HashMap<>();
        List<Long> offers = ids(citations, ChatSourceKind.OFFER);
        if (!offers.isEmpty()) {
            jdbc.sql(OFFER_SOURCES).param("ids", offers).query((ResultSet rs) -> {
                rows.put(new TurnLedger.Ref(ChatSourceKind.OFFER, rs.getLong("id")), row(rs));
            });
        }
        List<Long> applications = ids(citations, ChatSourceKind.APPLICATION);
        if (!applications.isEmpty()) {
            jdbc.sql(APPLICATION_SOURCES).param("ids", applications).query((ResultSet rs) -> {
                rows.put(new TurnLedger.Ref(ChatSourceKind.APPLICATION, rs.getLong("id")), row(rs));
            });
        }
        return rows;
    }

    private static List<ChatSource> sources(List<TurnLedger.Citation> citations, Map<TurnLedger.Ref, Row> rows) {
        List<ChatSource> sources = new ArrayList<>();
        for (TurnLedger.Citation citation : citations) {
            Row row = rows.get(new TurnLedger.Ref(citation.kind(), citation.id()));
            if (row != null) {
                sources.add(new ChatSource(
                        citation.n(),
                        citation.kind(),
                        citation.id(),
                        row.title(),
                        row.source(),
                        row.date(),
                        row.archived()));
            }
        }
        return sources;
    }

    /** Takes the turns and their tool calls with it, by the tables' own cascade. */
    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM chat_conversation WHERE id = :id")
                        .param("id", id)
                        .update()
                > 0;
    }

    private Map<Long, List<ChatStep>> steps(long conversationId) {
        Map<Long, List<ChatStep>> byTurn = new java.util.HashMap<>();
        jdbc.sql(STEPS).param("id", conversationId).query((ResultSet rs) -> {
            byTurn.computeIfAbsent(rs.getLong("turn_id"), turn -> new ArrayList<>())
                    .add(new ChatStep(
                            rs.getInt("ordinal"),
                            rs.getString("tool"),
                            rs.getString("label"),
                            ChatStepState.DONE,
                            rs.getInt("returned"),
                            rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms")));
        });
        return byTurn;
    }

    private static StoredTurn stored(ResultSet rs, int index) throws SQLException {
        return new StoredTurn(
                rs.getLong("id"),
                rs.getString("question"),
                rs.getString("answer_md"),
                ChatTurnState.valueOf(rs.getString("state")),
                (Long) rs.getObject("replaces_turn_id"),
                rs.getString("model"),
                rs.getTimestamp("created_at").toInstant(),
                citations(rs.getString("citations")));
    }

    private static List<Long> ids(List<TurnLedger.Citation> citations, ChatSourceKind kind) {
        return citations.stream()
                .filter(citation -> citation.kind() == kind)
                .map(TurnLedger.Citation::id)
                .toList();
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(
                rs.getString("title"),
                rs.getString("source_name"),
                rs.getTimestamp("at").toInstant().atZone(ZoneId.systemDefault()).toLocalDate(),
                rs.getBoolean("archived"));
    }

    /** The first question, on one line and cut at a word, as the conversation's name. */
    static String title(String question) {
        String line = question.strip().replaceAll("\\s+", " ");
        if (line.length() <= TITLE_LENGTH) {
            return line;
        }
        int cut = line.lastIndexOf(' ', TITLE_LENGTH);
        return line.substring(0, cut > TITLE_LENGTH / 2 ? cut : TITLE_LENGTH) + "…";
    }

    private static List<TurnLedger.Citation> citations(String json) {
        try {
            return json == null ? List.of() : JSON.readValue(json, CITATIONS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("chat_turn.citations does not parse: " + json, e);
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A tool's arguments as the model sent them, less any NUL; anything that is not a JSON object is
     * kept as text. An object is parsed and written again rather than edited as text, because the
     * escape sequence for U+0000 may just as well be an escaped backslash followed by the digits.
     */
    private static String arguments(String arguments) {
        try {
            JsonNode tree = arguments == null ? null : JSON.readTree(arguments);
            if (tree != null && tree.isObject()) {
                return json(withoutNul(tree));
            }
        } catch (JsonProcessingException e) {
            // Falls through: the model sent something that is not JSON, and that is worth keeping.
        }
        return json(java.util.Map.of("raw", arguments == null ? "" : withoutNul(arguments)));
    }

    private static JsonNode withoutNul(JsonNode node) {
        if (node.isTextual()) {
            return TextNode.valueOf(withoutNul(node.asText()));
        }
        if (node.isObject()) {
            ObjectNode clean = JSON.createObjectNode();
            node.properties().forEach(field -> clean.set(withoutNul(field.getKey()), withoutNul(field.getValue())));
            return clean;
        }
        if (node.isArray()) {
            ArrayNode clean = JSON.createArrayNode();
            node.forEach(item -> clean.add(withoutNul(item)));
            return clean;
        }
        return node;
    }

    /**
     * The text less any U+0000, which Postgres refuses in {@code TEXT} and {@code JSONB} alike.
     * Shared with the turn, which drops it from the model's text before streaming it, so what the
     * reader sees and what {@link #append} stores stay the same text.
     */
    static String withoutNul(String text) {
        return text == null ? null : text.replace("\0", "");
    }

    private record Row(String title, String source, LocalDate date, boolean archived) {}

    private record Header(long id, String title, Long pinnedOfferId, java.time.Instant updatedAt) {}

    /** A turn as read, before its sources are joined in with every other turn's. */
    private record StoredTurn(
            long id,
            String question,
            String answer,
            ChatTurnState state,
            Long replaces,
            String model,
            java.time.Instant createdAt,
            List<TurnLedger.Citation> citations) {

        TurnView view(List<ChatStep> steps, List<ChatSource> sources) {
            return new TurnView(id, question, answer, state, steps, sources, replaces, model, createdAt);
        }
    }
}
