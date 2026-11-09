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
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The candidate questions for an empty chat, one per met trigger, in a fixed order: the data
 * triggers first, then the two evergreen ones that are always there (ISC-455).
 *
 * <p>Rules find and a model phrases (ISC-456): {@link #candidates} calls none, {@link #suggestions}
 * answers the first {@link #SHOWN} as {@link Phrasing} stored them or as the catalog's own sentences,
 * and leaves the model call to {@link SuggestionWarmup} (ISC-527). A corpus that
 * meets no data trigger yields only the evergreen pair, each with its number, so the empty chat
 * always has a floor the data supports.
 */
@Slf4j
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
    private final SuggestionWarmup warmup;
    private final JdbcClient jdbc;

    /** The language the last open asked in: the one the unpinned suggestions are prepared in. */
    private final AtomicReference<String> lastLanguage = new AtomicReference<>();

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
     * What the empty chat shows: the first {@link #SHOWN} candidates, as the stored phrasing in
     * {@code language} when there is one and as the catalog's sentences otherwise. Never waits on a
     * model (ISC-527): a miss is handed to {@link SuggestionWarmup} as the job this request built,
     * and the next open finds it (ISC-538). Every open arms the warm-up for data changes (ISC-536).
     */
    public List<Suggestion> suggestions(SuggestionScope scope, String language) {
        SuggestionScope effective = scope == null ? SuggestionScope.NONE : scope;
        lastLanguage.set(language);
        warmup.opened();
        List<SuggestionCandidate> shown = shown(effective);
        Map<String, String> sentences = sentences(shown, language);
        String snapshot = snapshot(effective);
        Map<String, String> texts = sentences;
        Optional<String> key = sentences.isEmpty() ? Optional.empty() : phrasing.key(snapshot, sentences, language);
        if (key.isPresent()) {
            Optional<Map<String, String>> stored = phrasing.stored(key.get());
            if (stored.isPresent()) {
                texts = stored.get();
            } else if (phrasing.skipped(key.get())) {
                // Failed, or its model could not be built, a moment ago: the catalog until the
                // retry window has passed, and nothing queued meanwhile (ISC-535).
                log.debug("Suggestion key skipped until its retry window passes; the catalog answers");
            } else {
                SuggestionWarmup.Job job = new SuggestionWarmup.Job(key.get(), snapshot, sentences, language);
                if (effective.equals(SuggestionScope.NONE)) {
                    warmup.missed(job);
                } else {
                    warmup.pinned(job);
                }
            }
        }
        Map<String, String> shownTexts = texts;
        return shown.stream()
                .map(candidate ->
                        new Suggestion(candidate.trigger(), shownTexts.get(candidate.trigger()), candidate.count()))
                .toList();
    }

    /**
     * The unpinned suggestions as they stand now, in the language the last open asked in, ready to
     * be phrased after a data change; empty when there is nothing to phrase or no chat model to
     * phrase it. A miss never comes here: it hands over the job it already built (ISC-538).
     */
    Optional<SuggestionWarmup.Job> unpinnedJob() {
        String language = language();
        Map<String, String> sentences = sentences(shown(SuggestionScope.NONE), language);
        String snapshot = snapshot(SuggestionScope.NONE);
        if (sentences.isEmpty()) {
            return Optional.empty();
        }
        return phrasing.key(snapshot, sentences, language)
                .map(key -> new SuggestionWarmup.Job(key, snapshot, sentences, language));
    }

    /**
     * The language the last open asked in. A data change prepares nothing before the first open
     * since start (ISC-536), and that open sets it, so English is only a guard.
     */
    String language() {
        String last = lastLanguage.get();
        return last != null ? last : Catalog.ENGLISH;
    }

    private List<SuggestionCandidate> shown(SuggestionScope scope) {
        return candidates(scope).stream().limit(SHOWN).toList();
    }

    private static Map<String, String> sentences(List<SuggestionCandidate> shown, String language) {
        Map<String, String> sentences = new LinkedHashMap<>();
        shown.forEach(candidate -> sentences.put(candidate.trigger(), Catalog.sentence(language, candidate)));
        return sentences;
    }

    private String snapshot(SuggestionScope scope) {
        return "suggestions " + snapshot() + " " + scope;
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
