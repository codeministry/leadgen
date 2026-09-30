/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.suggest;

import de.codeministry.leadgen.config.ConfigRegistry;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The candidate questions for an empty chat, one per met trigger, in a fixed order: the data
 * triggers first, then the two evergreen ones that are always there (ISC-455).
 *
 * <p>Rules find and a model phrases (ISC-456): {@link #candidates} calls none, {@link #suggestions}
 * hands the first {@link #SHOWN} to {@link Phrasing}, which falls back to the catalog's own sentences. A corpus that
 * meets no data trigger yields only the evergreen pair, each with its number, so the empty chat
 * always has a floor the data supports.
 */
@Service
@RequiredArgsConstructor
public class SuggestionService {

    public static final String NEW_OFFERS = "newOffers";
    public static final String DEADLINES = "deadlines";
    public static final String NO_REPLY = "noReply";
    public static final String TAG_RISE = "tagRise";
    public static final String PINNED = "pinned";
    public static final String SHORTLIST = "shortlist";
    public static final String OPEN_APPLICATIONS = "openApplications";

    /** The order the candidates come in, whatever order Spring injects the triggers in. */
    static final List<String> ORDER =
            List.of(NEW_OFFERS, DEADLINES, NO_REPLY, TAG_RISE, PINNED, SHORTLIST, OPEN_APPLICATIONS);

    private final List<SuggestionTrigger> triggers;
    private final ConfigRegistry config;
    private final Phrasing phrasing;
    private final JdbcClient jdbc;

    public List<SuggestionCandidate> candidates(SuggestionScope scope) {
        SuggestionScope effective = scope == null ? SuggestionScope.NONE : scope;
        SuggestionThresholds thresholds = SuggestionThresholds.of(config);
        return triggers.stream()
                .sorted(Comparator.comparingInt(trigger -> rank(trigger.key())))
                .map(trigger -> trigger.evaluate(effective, thresholds))
                .flatMap(Optional::stream)
                .toList();
    }

    /** At most this many in the empty chat. */
    public static final int SHOWN = 4;

    /**
     * What the empty chat shows: the first {@link #SHOWN} candidates, phrased in {@code language}
     * by the chat model when one answers, the catalog's sentences otherwise.
     */
    public List<Suggestion> suggestions(SuggestionScope scope, String language) {
        SuggestionScope effective = scope == null ? SuggestionScope.NONE : scope;
        List<SuggestionCandidate> shown =
                candidates(effective).stream().limit(SHOWN).toList();
        Map<String, String> sentences = new LinkedHashMap<>();
        shown.forEach(candidate -> sentences.put(candidate.trigger(), Catalog.sentence(language, candidate)));
        Map<String, String> texts = phrasing.phrase("suggestions " + snapshot() + " " + effective, sentences, language);
        return shown.stream()
                .map(candidate ->
                        new Suggestion(candidate.trigger(), texts.get(candidate.trigger()), candidate.count()))
                .toList();
    }

    /** {@code en} or {@code de} from an {@code Accept-Language} header; English when neither ranks. */
    public static String language(String acceptLanguage) {
        return Catalog.language(acceptLanguage);
    }

    /**
     * The data snapshot a phrasing is cached against: the last run and the latest application
     * event. Everything a trigger reads moves one of the two, or moves a count, which is in the
     * phrasing's key as well.
     */
    private String snapshot() {
        return jdbc.sql("""
                        SELECT coalesce((SELECT max(id) FROM pipeline_run), 0)
                               || ':' || coalesce((SELECT max(id) FROM application_event), 0)
                        """).query(String.class).single();
    }

    private static int rank(String key) {
        int index = ORDER.indexOf(key);
        return index < 0 ? ORDER.size() : index;
    }
}
