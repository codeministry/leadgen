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
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    // A renamed conversation shows its own name. custom_title is NULL until a rename, and an
    // emptied rename stores NULL again, so the derived title is the fallback wherever it is read.
    private static final String SUMMARIES = """
            SELECT id, coalesce(custom_title, title) AS title, updated_at
              FROM chat_conversation
            """;

    private static final String NEWEST_FIRST = " ORDER BY updated_at DESC, id DESC";

    // What the search matches: the name the conversation shows and every question asked in it,
    // through the same normaliser as the query. The derived title is the first question, so a
    // renamed conversation still finds by it.
    private static final String SEARCHABLE = """
            SELECT concat_ws(' ',
                             nullif(coalesce(c.custom_title, c.title), ''),
                             (SELECT string_agg(t.question, ' ' ORDER BY t.ordinal)
                                FROM chat_turn t
                               WHERE t.conversation_id = c.id))
              FROM chat_conversation c
             WHERE c.id = :id
            """;

    private static final String CONVERSATION = """
            SELECT id, coalesce(custom_title, title) AS title, updated_at
              FROM chat_conversation
             WHERE id = :id
            """;

    private static final String CONTEXT = """
            SELECT conversation_id, kind, offer_id, query, window_from, window_to
              FROM chat_context
             WHERE conversation_id IN (:ids)
             ORDER BY conversation_id, ordinal
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

    private static final String STATISTICS = """
            SELECT c.turn_id, c.data::text AS data
              FROM chat_tool_call c
              JOIN chat_turn t ON t.id = c.turn_id
             WHERE t.conversation_id = :id AND c.data IS NOT NULL
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

    /** The longest name a rename stores; a longer one is cut, not refused. */
    static final int CUSTOM_TITLE_LENGTH = 120;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final TypeReference<List<TurnLedger.Citation>> CITATIONS = new TypeReference<>() {};

    /**
     * The most offers one conversation pins (ISC-453). Every pin is read in full at the start of
     * every turn and shares the lookup's advert budget, so past ten each advert is too short to
     * answer from; the eleventh is refused with this number rather than dropped without a word.
     */
    public static final int MAX_PINNED_OFFERS = 10;

    private final JdbcClient jdbc;

    /**
     * @param pinnedOfferId the offer "Ask about this offer" started it from, or null.
     * @return the new conversation's id. Its title stays empty until the first question names it.
     */
    public long create(Long pinnedOfferId) {
        return create(pinnedOfferId, null);
    }

    /**
     * A new conversation with its context. {@code pinnedOfferId}, still accepted for one release, is
     * the first chip; the list holding the same offer again keeps it at that first place.
     *
     * @return the new conversation's id. Its title stays empty until the first question names it.
     * @throws IllegalArgumentException when an item lacks what its kind needs
     * @throws org.springframework.dao.DataIntegrityViolationException when a pinned offer does not
     *     exist
     */
    @Transactional
    public long create(Long pinnedOfferId, List<ChatContextItem> context) {
        List<ChatContextItem> items = new ArrayList<>(context == null ? List.of() : context);
        if (pinnedOfferId != null) {
            items.addFirst(new ChatContextItem(ChatContextKind.OFFER, pinnedOfferId, null, null, null));
        }
        List<ChatContextItem> chips = chips(items);
        long id = jdbc.sql("INSERT INTO chat_conversation (title) VALUES ('') RETURNING id")
                .query(Long.class)
                .single();
        writeContext(id, chips);
        return id;
    }

    /**
     * Replaces a conversation's whole context: every row deleted and the list inserted in its order,
     * in one transaction, so a reader sees the old list or the new one and never half of each.
     *
     * @return false when no conversation has this id
     * @throws IllegalArgumentException when an item lacks what its kind needs
     * @throws org.springframework.dao.DataIntegrityViolationException when a pinned offer does not
     *     exist
     */
    @Transactional
    public boolean replaceContext(long id, List<ChatContextItem> context) {
        List<ChatContextItem> chips = chips(context == null ? List.of() : context);
        // The row lock orders two replacements of the same conversation one after the other; without
        // it both would delete, and the second insert would collide on the ordinals of the first.
        boolean found = jdbc.sql("SELECT id FROM chat_conversation WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(Long.class)
                .optional()
                .isPresent();
        if (!found) {
            return false;
        }
        jdbc.sql("DELETE FROM chat_context WHERE conversation_id = :id")
                .param("id", id)
                .update();
        writeContext(id, chips);
        return true;
    }

    /**
     * Newest first: the conversation whose last turn started most recently leads. Its last activity
     * is that same start, {@code updated_at}.
     */
    public List<ConversationSummary> list() {
        return withContext(jdbc.sql(SUMMARIES + NEWEST_FIRST)
                .query(ConversationRepository::summary)
                .list());
    }

    /**
     * The conversations whose name or any question holds every word of {@code q}, newest first.
     * Case and accents are ignored on both sides: the query goes through {@link #searchable}, the
     * stored {@code search_text} went through it on its last write. Each word is a substring match,
     * with {@code %} and {@code _} in it taken literally. A query without a word is the whole list.
     */
    public List<ConversationSummary> search(String q) {
        List<String> words = Arrays.stream(searchable(q).split("\\s+"))
                .filter(word -> !word.isEmpty())
                .distinct()
                .toList();
        if (words.isEmpty()) {
            return list();
        }
        StringBuilder where = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < words.size(); i++) {
            where.append(i == 0 ? " WHERE " : " AND ")
                    .append("search_text LIKE :word")
                    .append(i)
                    .append(" ESCAPE '\\'");
            params.put("word" + i, "%" + literal(words.get(i)) + "%");
        }
        return withContext(jdbc.sql(SUMMARIES + where + NEWEST_FIRST)
                .params(params)
                .query(ConversationRepository::summary)
                .list());
    }

    /**
     * Names a conversation, or clears its name when {@code title} is null or blank so the derived
     * title shows again. Stored trimmed and cut to {@value #CUSTOM_TITLE_LENGTH} characters. A
     * rename is no activity: the conversation keeps its place in the list.
     *
     * @return false when no conversation has this id
     */
    @Transactional
    public boolean rename(long id, String title) {
        boolean found = jdbc.sql("UPDATE chat_conversation SET custom_title = :title WHERE id = :id")
                        .param("title", customTitle(title))
                        .param("id", id)
                        .update()
                > 0;
        if (found) {
            reindex(id);
        }
        return found;
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
        Map<Long, List<StatisticsSource>> statistics = statistics(id);
        List<TurnView> turns = stored.stream()
                .map(turn -> {
                    // The same order the live `sources` event had: cited rows, then statistics calls.
                    List<ChatSourceItem> sources = new ArrayList<>(sources(turn.citations(), rows));
                    sources.addAll(statistics.getOrDefault(turn.id(), List.of()));
                    return turn.view(steps.getOrDefault(turn.id(), List.of()), List.copyOf(sources));
                })
                .toList();
        Header h = header.get();
        List<ChatContextItem> context = contexts(List.of(id)).getOrDefault(id, List.of());
        return Optional.of(new ConversationView(h.id(), h.title(), firstOffer(context), context, turns, h.updatedAt()));
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
        long turnId = jdbc.sql(START_TURN)
                .param("conversation", conversationId)
                .param("question", question)
                .param("model", model)
                .param("replaces", replaces)
                .query(Long.class)
                .single();
        reindex(conversationId);
        return turnId;
    }

    /**
     * The conversation's whole context in its order — what a turn hands its tools (ISC-452) and
     * what its pinned lookup reads (ISC-453). Empty for a conversation without one.
     */
    public List<ChatContextItem> context(long conversationId) {
        return contexts(List.of(conversationId)).getOrDefault(conversationId, List.of());
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
                            INSERT INTO chat_tool_call (turn_id, ordinal, tool, label, arguments, returned_ids,
                                                        duration_ms, data)
                            VALUES (:turn, :ordinal, :tool, :label, CAST(:arguments AS jsonb),
                                    CAST(:returned AS jsonb), :duration, CAST(:data AS jsonb))
                            """)
                    .param("data", call.data() == null ? null : json(call.data()))
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

    /** Each turn's statistics sources, in call order, as {@code chat_tool_call.data} stored them. */
    private Map<Long, List<StatisticsSource>> statistics(long conversationId) {
        Map<Long, List<StatisticsSource>> byTurn = new HashMap<>();
        jdbc.sql(STATISTICS).param("id", conversationId).query((ResultSet rs) -> {
            try {
                byTurn.computeIfAbsent(rs.getLong("turn_id"), turn -> new ArrayList<>())
                        .add(JSON.readValue(rs.getString("data"), StatisticsSource.class));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("chat_tool_call.data of turn " + rs.getLong("turn_id"), e);
            }
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

    /** Rewrites what the search matches from the conversation as it now stands. */
    private void reindex(long id) {
        String text = jdbc.sql(SEARCHABLE).param("id", id).query(String.class).single();
        jdbc.sql("UPDATE chat_conversation SET search_text = :text WHERE id = :id")
                .param("text", searchable(text))
                .param("id", id)
                .update();
    }

    /**
     * Lower-cased, and every accent dropped: decomposed (NFD) so an accent is a mark of its own,
     * then the marks removed. "Köln", "KOLN" and "koln" all become "koln".
     */
    static String searchable(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    /** A word matched as it is typed: the escape character, {@code %} and {@code _} escaped for LIKE. */
    private static String literal(String word) {
        return word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** A rename as stored: trimmed, at most {@value #CUSTOM_TITLE_LENGTH} characters, null when empty. */
    static String customTitle(String title) {
        if (title == null) {
            return null;
        }
        String name = title.strip();
        if (name.length() > CUSTOM_TITLE_LENGTH) {
            int end = CUSTOM_TITLE_LENGTH;
            if (Character.isHighSurrogate(name.charAt(end - 1))) {
                end--;
            }
            name = name.substring(0, end).strip();
        }
        return name.isEmpty() ? null : name;
    }

    private static ConversationSummary summary(ResultSet rs, int index) throws SQLException {
        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
        return new ConversationSummary(rs.getLong("id"), rs.getString("title"), updatedAt, updatedAt, List.of());
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

    /** The summaries again, each with its context, read in one query for the whole list. */
    private List<ConversationSummary> withContext(List<ConversationSummary> summaries) {
        Map<Long, List<ChatContextItem>> contexts =
                contexts(summaries.stream().map(ConversationSummary::id).toList());
        return summaries.stream()
                .map(s -> new ConversationSummary(
                        s.id(), s.title(), s.updatedAt(), s.lastActivityAt(), contexts.getOrDefault(s.id(), List.of())))
                .toList();
    }

    /** The context of each conversation, in ordinal order; a conversation without one is absent. */
    private Map<Long, List<ChatContextItem>> contexts(List<Long> conversations) {
        Map<Long, List<ChatContextItem>> byConversation = new HashMap<>();
        if (conversations.isEmpty()) {
            return byConversation;
        }
        jdbc.sql(CONTEXT).param("ids", conversations).query((ResultSet rs) -> {
            byConversation
                    .computeIfAbsent(rs.getLong("conversation_id"), c -> new ArrayList<>())
                    .add(new ChatContextItem(
                            ChatContextKind.valueOf(rs.getString("kind")),
                            (Long) rs.getObject("offer_id"),
                            rs.getString("query"),
                            rs.getObject("window_from", LocalDate.class),
                            rs.getObject("window_to", LocalDate.class)));
        });
        return byConversation;
    }

    /**
     * Inserts the chips at ordinals 1..n and mirrors the first offer into {@code pinned_offer_id},
     * which the turn still reads for one release.
     */
    private void writeContext(long id, List<ChatContextItem> chips) {
        for (int i = 0; i < chips.size(); i++) {
            ChatContextItem chip = chips.get(i);
            jdbc.sql("""
                            INSERT INTO chat_context (conversation_id, kind, offer_id, query, window_from, window_to, ordinal)
                            VALUES (:conversation, :kind, :offer, :query, :from, :to, :ordinal)
                            """)
                    .param("conversation", id)
                    .param("kind", chip.kind().name())
                    .param("offer", chip.offerId())
                    .param("query", chip.query())
                    .param("from", chip.from())
                    .param("to", chip.to())
                    .param("ordinal", i + 1)
                    .update();
        }
        jdbc.sql("UPDATE chat_conversation SET pinned_offer_id = :pin WHERE id = :id")
                .param("pin", firstOffer(chips))
                .param("id", id)
                .update();
    }

    /**
     * The list as it is stored: each item cut down to its own kind's fields, an empty query for a
     * shortlist view without one, and the same offer only at its first place — one chip, not two.
     *
     * @throws IllegalArgumentException for an item without a kind, an offer without an id, or a
     *     window without both days in order
     */
    static List<ChatContextItem> chips(List<ChatContextItem> items) {
        List<ChatContextItem> chips = new ArrayList<>();
        java.util.Set<Long> offers = new java.util.HashSet<>();
        for (ChatContextItem item : items) {
            if (item == null || item.kind() == null) {
                throw new IllegalArgumentException("a context item needs a kind");
            }
            switch (item.kind()) {
                case OFFER -> {
                    if (item.offerId() == null) {
                        throw new IllegalArgumentException("an OFFER context item needs an offerId");
                    }
                    if (offers.add(item.offerId())) {
                        if (offers.size() > MAX_PINNED_OFFERS) {
                            throw new IllegalArgumentException("a conversation holds at most " + MAX_PINNED_OFFERS
                                    + " pinned offers; remove one before pinning another");
                        }
                        chips.add(new ChatContextItem(ChatContextKind.OFFER, item.offerId(), null, null, null));
                    }
                }
                case SHORTLIST_VIEW ->
                    chips.add(new ChatContextItem(
                            ChatContextKind.SHORTLIST_VIEW,
                            null,
                            item.query() == null ? "" : item.query(),
                            null,
                            null));
                case ANALYTICS_WINDOW -> {
                    if (item.from() == null || item.to() == null || item.from().isAfter(item.to())) {
                        throw new IllegalArgumentException(
                                "an ANALYTICS_WINDOW context item needs from and to, from not after to");
                    }
                    chips.add(
                            new ChatContextItem(ChatContextKind.ANALYTICS_WINDOW, null, null, item.from(), item.to()));
                }
            }
        }
        return chips;
    }

    /** The first pinned offer of a context, or null; what {@code pinnedOfferId} still reports. */
    private static Long firstOffer(List<ChatContextItem> context) {
        return context.stream()
                .filter(item -> item.kind() == ChatContextKind.OFFER)
                .map(ChatContextItem::offerId)
                .findFirst()
                .orElse(null);
    }

    private record Row(String title, String source, LocalDate date, boolean archived) {}

    private record Header(long id, String title, java.time.Instant updatedAt) {}

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

        TurnView view(List<ChatStep> steps, List<ChatSourceItem> sources) {
            return new TurnView(id, question, answer, state, steps, sources, replaces, model, createdAt);
        }
    }
}
