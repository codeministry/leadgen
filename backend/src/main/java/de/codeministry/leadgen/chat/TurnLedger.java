/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What one turn's tools returned, and the order its answer cited it in.
 *
 * <p><b>The grounding check holds a citation against this and nothing else.</b> An id the model
 * names is a source only when a tool of this very turn returned it: an id from an earlier turn,
 * from the model's own memory or made up looks exactly the same in the text, and only the ledger
 * tells them apart. The calls it records are also what {@code chat_tool_call} stores, so the
 * stored conversation can say later why a citation was a link.
 *
 * <p>One per turn, never a bean. Synchronised because a model may ask for several tools at once
 * and a client may run them side by side.
 */
public class TurnLedger {

    /**
     * One row a tool returned.
     *
     * @param kind offer or application — the same number means a different row in each.
     * @param id   the row's id.
     */
    public record Ref(ChatSourceKind kind, long id) {}

    /**
     * One tool call of the turn, as {@code chat_tool_call} stores it.
     *
     * @param ordinal    position within the turn, from 1.
     * @param tool       the tool's name as the model called it.
     * @param label      what the call did, in words the screen shows.
     * @param arguments  the arguments as JSON.
     * @param returned   the rows it returned.
     * @param durationMs how long it took.
     */
    public record Call(int ordinal, String tool, String label, String arguments, List<Ref> returned, long durationMs) {}

    /**
     * A row the answer cited, with the number its links carry.
     *
     * @param n    from 1, in the order the answer first cited the rows.
     * @param kind offer or application.
     * @param id   the row's id.
     */
    public record Citation(int n, ChatSourceKind kind, long id) {}

    private final List<Call> calls = new ArrayList<>();
    private final Set<Ref> returned = new HashSet<>();
    private final Map<Ref, Integer> cited = new LinkedHashMap<>();

    /** Records a finished tool call and the rows it returned; the ordinal is the next one. */
    public synchronized Call record(String tool, String label, String arguments, List<Ref> rows, long durationMs) {
        Call call = new Call(calls.size() + 1, tool, label, arguments, List.copyOf(rows), durationMs);
        calls.add(call);
        returned.addAll(call.returned());
        return call;
    }

    /** The calls in the order they were recorded. */
    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    /** Whether any tool of this turn returned this row. */
    public synchronized boolean contains(ChatSourceKind kind, long id) {
        return returned.contains(new Ref(kind, id));
    }

    /**
     * The number of this row's citations: a new number on its first citation, the same one after.
     * Called only for a citation that passed the grounding check, so an unverified id never takes
     * a number and never appears in the sources.
     */
    public synchronized int cite(ChatSourceKind kind, long id) {
        return cited.computeIfAbsent(new Ref(kind, id), ref -> cited.size() + 1);
    }

    /** The cited rows in first-citation order; what {@link ChatSources} is built from. */
    public synchronized List<Citation> citations() {
        return cited.entrySet().stream()
                .map(entry -> new Citation(
                        entry.getValue(), entry.getKey().kind(), entry.getKey().id()))
                .toList();
    }
}
