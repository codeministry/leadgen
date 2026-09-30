/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.mcp;

import de.codeministry.leadgen.chat.tools.ApplicationResult;
import de.codeministry.leadgen.chat.tools.ApplicationTool;
import de.codeministry.leadgen.chat.tools.ProfileResult;
import de.codeministry.leadgen.chat.tools.ProfileTool;
import de.codeministry.leadgen.chat.tools.SemanticSearchResult;
import de.codeministry.leadgen.chat.tools.SemanticSearchTool;
import de.codeministry.leadgen.chat.tools.StatisticsResult;
import de.codeministry.leadgen.chat.tools.StatisticsTool;
import de.codeministry.leadgen.llm.LlmBudget;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The four chat tools codeministry-mcp never had, served without a chat turn (ISC-484): each one
 * calls the chat's own tool class, so both surfaces answer the same arguments the same way.
 *
 * <p>No turn means no conversation, no pinned context, no turn ledger and no call against the
 * chat's daily ceiling. The one tool that asks a model, the search by meaning, takes leadgen's
 * {@code llm.budget} instead, the ceiling {@code leadgen_search_offers} and the shortlist's own
 * search already draw on.
 */
@Component
@RequiredArgsConstructor
public class ChatToolsForMcp {

    private final SemanticSearchTool semantic;
    private final StatisticsTool statistics;
    private final ApplicationTool application;
    private final ProfileTool profile;
    private final LlmBudget llmBudget;

    @McpTool(
            name = "leadgen_semantic_search",
            description = "Finds offers whose advert is closest in meaning to the given words, nearest first,"
                    + " across the working shortlist and the archive; each offer says whether it is"
                    + " archived. Use it for 'anything like ...' questions and leadgen_search_offers for"
                    + " filters and counts. When 'unavailable' is set this installation cannot search by"
                    + " meaning.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public SemanticSearchResult semanticSearch(
            @McpToolParam(
                            description = "What the offers should be about, in a few words, e.g. 'event streaming"
                                    + " with Kafka'.")
                    String query) {
        return semantic.searchByMeaning(query, llmBudget::take);
    }

    @McpTool(
            name = "leadgen_statistics",
            description = "The numbers the dashboard and analytics screens show: the filter funnel, offers"
                    + " per day, pipeline runs per day, the score histogram and bands, applications per"
                    + " status with reply times, the last run, the market, the knockouts per day and stage,"
                    + " and the scoring scales in use. 'from' and 'to' narrow only the per-day series; the"
                    + " result says which days were covered. With 'compareFrom' and 'compareTo' it also"
                    + " carries 'comparison' and 'differences', this window's totals minus the comparison's.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public StatisticsResult statistics(
            @McpToolParam(description = "First day to include, as YYYY-MM-DD.", required = false) String from,
            @McpToolParam(description = "Last day to include, as YYYY-MM-DD.", required = false) String to,
            @McpToolParam(description = "First day of a window to compare with, as YYYY-MM-DD.", required = false)
                    String compareFrom,
            @McpToolParam(description = "Last day of a window to compare with, as YYYY-MM-DD.", required = false)
                    String compareTo) {
        // The overload without pins: an MCP client has no conversation to pin a window to.
        return statistics.statistics(from, to, compareFrom, compareTo);
    }

    @McpTool(
            name = "leadgen_application",
            description = "The application for one offer: its status, every status change newest first,"
                    + " dates, the note, and the cover letter's text if one was written. Look it up by the"
                    + " offer's id, as leadgen_search_offers returns it.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public ApplicationResult application(
            @McpToolParam(description = "The offer's id, as the other tools return it.") long offerId) {
        return application.application(offerId);
    }

    @McpTool(
            name = "leadgen_profile",
            description = "The skill profile in force: roles, seniority, skills by tier with weights,"
                    + " industries, the topics that lift or sink a score, the reference projects a cover"
                    + " letter may cite, and languages.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public ProfileResult profile() {
        return profile.profile();
    }
}
