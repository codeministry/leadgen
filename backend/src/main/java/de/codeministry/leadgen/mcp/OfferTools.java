/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.mcp;

import de.codeministry.leadgen.llm.LlmBudget;
import de.codeministry.leadgen.offer.OfferQueryService;
import de.codeministry.leadgen.offer.ShortlistEntry;
import de.codeministry.leadgen.offer.ShortlistParams;
import java.util.List;
import java.util.NoSuchElementException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The shortlist as MCP tools: {@code leadgen_search_offers} and {@code leadgen_get_offer}, on the
 * same {@link OfferQueryService} the shortlist screen reads. A semantic search embeds its phrase
 * against the run's call budget, exactly as {@code GET /offers?semantic=} does.
 */
@Component
@RequiredArgsConstructor
public class OfferTools {

    private final OfferQueryService offers;
    private final LlmBudget llmBudget;

    @McpTool(
            name = "leadgen_search_offers",
            description = "Search the leadgen shortlist of freelance project offers. Returns compact"
                    + " summaries (title, agency, portal, location, remote share, duration,"
                    + " rate, score, tags, url) — not the advert text. Use leadgen_get_offer"
                    + " for the full advert of one offer. Results are capped; the response"
                    + " always reports how many matched in total and carries a nextCursor"
                    + " when more exist.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public OfferSearchResult searchOffers(
            @McpToolParam(description = "Free-text search over title, description and tags.", required = false)
                    String q,
            @McpToolParam(
                            description = "Semantic search phrase. Ranks by meaning rather than keyword;"
                                    + " cannot be combined with similarToOfferId.",
                            required = false)
                    String semantic,
            @McpToolParam(description = "Find offers similar to this offer id.", required = false)
                    Long similarToOfferId,
            @McpToolParam(
                            description = "Score band, e.g. 'high'. Cannot be combined with minScore/maxScore.",
                            required = false)
                    String band,
            @McpToolParam(description = "Minimum score, 0-100.", required = false) Integer minScore,
            @McpToolParam(description = "Maximum score, 0-100.", required = false) Integer maxScore,
            @McpToolParam(description = "Restrict to one portal, e.g. 'freelancermap'.", required = false)
                    String portal,
            @McpToolParam(description = "Include archived offers. Default false.", required = false) Boolean archived,
            @McpToolParam(description = "Sort key as leadgen defines it, e.g. 'score'.", required = false) String sort,
            @McpToolParam(description = "Minimum project duration in months.", required = false) Integer minMonths,
            @McpToolParam(description = "Only offers whose application deadline has not passed.", required = false)
                    Boolean deadlineOpen,
            @McpToolParam(description = "Maximum rows to return, 1-50. Default 20.", required = false) Integer limit,
            @McpToolParam(description = "Opaque cursor from a previous response's nextCursor.", required = false)
                    String cursor) {
        var params = new ShortlistParams(
                q,
                band,
                minScore,
                maxScore,
                null,
                portal == null || portal.isBlank() ? null : List.of(portal),
                archived,
                sort,
                null,
                semantic,
                similarToOfferId,
                minMonths,
                deadlineOpen,
                null,
                null,
                cursor,
                Rows.clamp(limit, Rows.DEFAULT));
        var page = offers.shortlist(params.query(), llmBudget::take);
        var rows = page.entries().stream().map(OfferSummary::of).toList();
        return new OfferSearchResult(
                rows,
                rows.size(),
                page.matched(),
                page.total(),
                page.nextCursor(),
                page.nextCursor() != null ? Boolean.TRUE : null);
    }

    @McpTool(
            name = "leadgen_get_offer",
            description = "Fetch one offer by id with its full detail: description, score reasons,"
                    + " flags and sources. Set includeFullText to get the complete advert"
                    + " text as well, which is large.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public ShortlistEntry getOffer(
            @McpToolParam(description = "The offer id.", required = true) long id,
            @McpToolParam(
                            description = "Include the complete advert text. Default false, because it is long.",
                            required = false)
                    Boolean includeFullText) {
        var entry = offers.find(id).orElseThrow(() -> new NoSuchElementException("no offer " + id));
        // The advert comes whole or not at all: the blocks the detail reads it in are the same text.
        boolean whole = Boolean.TRUE.equals(includeFullText);
        return new ShortlistEntry(
                entry.offer().forMcpClient(whole),
                entry.score(),
                entry.flags(),
                entry.sources(),
                whole ? entry.content() : List.of());
    }
}
