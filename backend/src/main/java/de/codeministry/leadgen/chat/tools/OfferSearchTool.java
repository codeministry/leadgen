/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.tools;

import de.codeministry.leadgen.chat.ChatBudget;
import de.codeministry.leadgen.offer.OfferQueryService;
import de.codeministry.leadgen.offer.RelatedFilter;
import de.codeministry.leadgen.offer.ScoreFilter;
import de.codeministry.leadgen.offer.ScoreState;
import de.codeministry.leadgen.offer.ShortlistEntry;
import de.codeministry.leadgen.offer.ShortlistParams;
import de.codeministry.leadgen.offer.ShortlistQuery;
import de.codeministry.leadgen.offer.ShortlistSort;
import de.codeministry.leadgen.offer.StartWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * The shortlist, as a tool the chat model can call.
 *
 * <p><b>It asks {@link OfferQueryService#shortlist} and nothing else</b>, with the query built the
 * way {@code OfferController} builds it from the same parameters, so the ids and their order are
 * the shortlist's by construction rather than by a second query that happens to agree. A list the
 * chat names is therefore a list the screen shows for the same filters; {@code ChatToolsTest}
 * holds that against the endpoint over HTTP. The string filters resolve through the same
 * {@code of(...)} the controller uses, so a sort or a window nobody defined is refused with the
 * controller's sentence, which Spring AI hands back to the model as the tool's answer.
 *
 * <p><b>Which of the shortlist's filters are here, and which are not.</b> Text, score band or
 * range, portal, start window, minimum length, open deadline, profile topic and sort are what a
 * question in plain words asks for. The archive side is fixed to the working list, which is what
 * "the shortlist" means on every other screen. Relatedness is left to the semantic tool, which
 * searches the archive as well and says so per hit; one narrowing with two tools behind it would
 * make the model choose between them for the same words. The possible-duplicates switch and the
 * cursor are screen mechanics, not questions.
 *
 * <p><b>One filter the screen does not have: when the offer came in.</b> "This month's offers"
 * is a question the chat is asked and the shortlist has no date control for. The window rides
 * on {@link ShortlistQuery} as two fields the controller never binds, so the same SQL filters,
 * counts and orders it; applied to the capped page afterwards it would lose every match past
 * the page and report the page's count as {@code matched}.
 *
 * <p>It never returns the advert's text, its content blocks, the agency, the portal links or
 * anything read from a mail: {@link OfferHit} is the whole shape.
 *
 * <p>Read-only: the list comes from a read service, and the one write is the chat's own call
 * counter, taken when a topic's name has to be embedded for its paraphrase half — from the chat's
 * day, never from {@code llm.budget} (ISC-433), exactly as the semantic tool pays for its phrase.
 */
@Component
@RequiredArgsConstructor
public class OfferSearchTool {

    /**
     * The page the tool answers with, half the screen's.
     *
     * <p>Twenty-five rows of id, title, source, date and score are about a thousand tokens, which
     * a local model with a small context can still reason over beside the question and the
     * conversation; the screen's fifty would double that for rows an answer rarely names. The
     * count of everything matched travels beside the page ({@link OfferSearchResult#matched()}),
     * so the cap shortens the list and never the number, and the operator follows the link to
     * the shortlist for the rest.
     */
    public static final int PAGE = 25;

    private final OfferQueryService offers;

    /** Pays a topic's embedding; writes only the chat's own {@code chat_call_budget}. */
    private final ChatBudget budget;

    @Tool(
            name = "search_offers",
            description = "Lists offers on the working shortlist that match the given filters, in the"
                    + " shortlist's own order, and says how many matched in all. Returns at most "
                    + PAGE
                    + " offers; 'matched' is the full count. Every filter is optional: leave out"
                    + " whatever the question does not ask for. Cite an offer by its id. When the"
                    + " conversation has pinned shortlist views, the pinned view's filters replace"
                    + " every other argument; 'view' picks which one.")
    public OfferSearchResult searchOffers(
            @ToolParam(
                            required = false,
                            description = "Which pinned shortlist view to list, 1 for the first; only when the"
                                    + " conversation has pinned views.")
                    Integer view,
            ToolContext toolContext,
            @ToolParam(
                            required = false,
                            description = "Words to find in the title, the tags or the advert text, e.g. 'kafka'.")
                    String text,
            @ToolParam(
                            required = false,
                            description = "A score band: 'shortlist' (strong fit), 'review' (worth a look) or"
                                    + " 'discarded' (below the review line). Do not combine with minScore or"
                                    + " maxScore.")
                    String band,
            @ToolParam(required = false, description = "Lowest score to include, 0 to 100.") Integer minScore,
            @ToolParam(required = false, description = "Highest score to include, 0 to 100.") Integer maxScore,
            @ToolParam(
                            required = false,
                            description = "Portal names to include; an offer matches when any portal it was seen"
                                    + " on is listed.")
                    List<String> portals,
            @ToolParam(
                            required = false,
                            description = "When the engagement starts: 'now' (already started), 'soon' (within"
                                    + " 30 days), 'later' (after that) or 'unknown' (not stated).")
                    String startWindow,
            @ToolParam(
                            required = false,
                            description =
                                    "Minimum stated length in months; offers that state no length are left" + " out.")
                    Integer minMonths,
            @ToolParam(
                            required = false,
                            description = "True for only offers whose application deadline has not passed,"
                                    + " including those that state none.")
                    Boolean deadlineOpen,
            @ToolParam(required = false, description = "A topic from the operator's skill profile, by its name.")
                    String topic,
            @ToolParam(
                            required = false,
                            description = "Order: 'score' (default, best first), 'score-asc', 'fresh' (newest"
                                    + " first), 'fresh-asc', 'start', 'start-desc', 'deadline', 'deadline-desc',"
                                    + " 'duration' (longest first) or 'duration-asc'.")
                    String sort,
            @ToolParam(
                            required = false,
                            description = "Only offers that came in on or after this day, as YYYY-MM-DD,"
                                    + " e.g. '2026-08-01'.")
                    String cameAfter,
            @ToolParam(
                            required = false,
                            description = "Only offers that came in before this day, as YYYY-MM-DD; the day"
                                    + " itself is excluded, so August is cameAfter '2026-08-01' and"
                                    + " cameBefore '2026-09-01'.")
                    String cameBefore) {
        List<String> views = PinnedContext.of(toolContext).views();
        if (!views.isEmpty()) {
            return search(pinnedView(views, view));
        }
        return searchOffers(
                text,
                band,
                minScore,
                maxScore,
                portals,
                startWindow,
                minMonths,
                deadlineOpen,
                topic,
                sort,
                cameAfter,
                cameBefore);
    }

    /** The search without pins: the model's arguments are the filters. */
    public OfferSearchResult searchOffers(
            String text,
            String band,
            Integer minScore,
            Integer maxScore,
            List<String> portals,
            String startWindow,
            Integer minMonths,
            Boolean deadlineOpen,
            String topic,
            String sort,
            String cameAfter,
            String cameBefore) {
        var query = new ShortlistQuery(
                        text,
                        new ScoreFilter(band, minScore, maxScore, ScoreState.ANY),
                        portals,
                        false,
                        ShortlistSort.of(sort),
                        StartWindow.of(startWindow),
                        RelatedFilter.ANY,
                        minMonths,
                        Boolean.TRUE.equals(deadlineOpen),
                        false,
                        topic,
                        null,
                        PAGE)
                .withCameIn(startOf(cameAfter, "cameAfter"), startOf(cameBefore, "cameBefore"));
        return search(query);
    }

    /**
     * A pinned view as the query the screen runs for it (ISC-452): its query string bound by the
     * endpoint's own {@link ShortlistParams}, so every filter, the archive side and the sort are the
     * screen's and none of the model's arguments survive. Only the page is the tool's — no cursor,
     * and {@link #PAGE} rows — so the ids are the screen's first page, in its order.
     */
    static ShortlistQuery pinnedView(List<String> views, Integer view) {
        int index = view == null ? 1 : view;
        if (index < 1 || index > views.size()) {
            throw new IllegalArgumentException("view must be 1 to " + views.size() + ", the pinned shortlist views");
        }
        ShortlistQuery screen = ShortlistParams.parse(views.get(index - 1)).query();
        return new ShortlistQuery(
                screen.q(),
                screen.score(),
                screen.portals(),
                screen.archived(),
                screen.sort(),
                screen.startWindow(),
                screen.related(),
                screen.minMonths(),
                screen.deadlineOpen(),
                screen.possibleDuplicates(),
                screen.topic(),
                null,
                PAGE);
    }

    private OfferSearchResult search(ShortlistQuery query) {
        // ISC-433: a topic's paraphrase half embeds the topic's name, paid from the chat's day.
        var page = offers.shortlist(query, budget::take);
        return new OfferSearchResult(
                page.matched(),
                page.entries().stream().map(OfferSearchTool::hit).toList());
    }

    /**
     * A day as the instant it starts, in the zone the analytics screen buckets its days in.
     *
     * <p>The system zone, because that is what {@code AnalyticsQueryService} reports as its own:
     * "offers that came in in August" then counts the same days the intake chart calls August.
     * A day and not an instant, because a model writes dates reliably and offsets rarely; an
     * unreadable one is refused by {@link Days#parse}, the same sentence the statistics tool gives.
     */
    static Instant startOf(String day, String name) {
        LocalDate parsed = Days.parse(day, name);
        return parsed == null
                ? null
                : parsed.atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    /** One entry as a hit; shared with {@link PinnedOfferLookup}, so both hand the model one shape. */
    static OfferHit hit(ShortlistEntry entry) {
        var offer = entry.offer();
        return new OfferHit(
                offer.id(),
                offer.title(),
                offer.sourceName(),
                offer.ingestedAt(),
                entry.score() == null ? null : entry.score().value(),
                offer.archivedAt() != null);
    }
}
