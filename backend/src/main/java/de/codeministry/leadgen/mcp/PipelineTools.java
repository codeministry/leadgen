/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.mcp;

import de.codeministry.leadgen.analytics.AnalyticsQueryService;
import de.codeministry.leadgen.analytics.LastRunQueryService;
import de.codeministry.leadgen.application.ApplicationStatus;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.RulesView;
import de.codeministry.leadgen.config.SourceQueryService;
import de.codeministry.leadgen.offer.OfferQueryService;
import de.codeministry.leadgen.score.PromptCatalog;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * How the pipeline stands and what decides it: {@code leadgen_funnel_stats},
 * {@code leadgen_ingest_status} and {@code leadgen_get_pipeline_config}, each on the service the
 * matching screen reads, answering the records those screens receive.
 */
@Component
@RequiredArgsConstructor
public class PipelineTools {

    private final OfferQueryService offers;
    private final AnalyticsQueryService analytics;
    private final LastRunQueryService runs;
    private final SourceQueryService sources;
    private final ConfigRegistry config;
    private final PromptCatalog prompts;

    /**
     * The section names the funnel tool's descriptions spell out, twice. An annotation takes a constant
     * only, so these are written out and {@code PipelineToolsTest} holds them to
     * {@link AnalyticsQueryService#SECTIONS}.
     */
    static final String SECTION_NAMES = "intake, market, scores, applications, runs";

    static final String QUOTED_SECTION_NAMES = "'intake', 'market', 'scores', 'applications', 'runs'";

    @McpTool(
            name = "leadgen_funnel_stats",
            description = "How offers moved through the pipeline: the funnel stages with how many each"
                    + " removed, and the application lanes. Pass an analytics section"
                    + " (" + QUOTED_SECTION_NAMES + ") to get that"
                    + " slice of the full analytics view instead.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public FunnelStats funnelStats(
            @McpToolParam(
                            description = "One of " + SECTION_NAMES + ". Omit for the funnel and lanes overview.",
                            required = false)
                    String section) {
        if (section == null || section.isBlank()) {
            return new FunnelStats.Overview(offers.funnel(), ApplicationStatus.LANES);
        }
        // The one section asked for, not the whole analytics view with four others thrown away; read
        // as leadgen_list_applications reads its status, trimmed and in any case.
        String name = section.trim().toLowerCase(Locale.ROOT);
        return analytics
                .section(name)
                .<FunnelStats>map(data -> new FunnelStats.Section(name, Instant.now(), data))
                .orElseGet(() ->
                        new FunnelStats.Unknown("unknown section '" + section + "'", AnalyticsQueryService.SECTIONS));
    }

    @McpTool(
            name = "leadgen_ingest_status",
            description = "The last completed ingest run and the one in flight, if any: per-source"
                    + " counts, what each filter removed, and how many reached the"
                    + " shortlist. Each stage row carries its status and duration; an OK stage that"
                    + " worked on several adverts at once also carries `width` and the note"
                    + " `width=N`, which is how wide it ran, not an error.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public IngestStatus ingestStatus() {
        return new IngestStatus(
                runs.lastRun().orElse(null),
                runs.currentRun().<Object>map(run -> run).orElse(IngestStatus.NO_RUN));
    }

    @McpTool(
            name = "leadgen_get_pipeline_config",
            description = "The configuration that decides what survives and how it is scored: matching"
                    + " rules with weights, penalties and knockouts; the scoring models;"
                    + " the configured sources; or the prompts.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public Object getPipelineConfig(
            @McpToolParam(description = "One of rules, scoring-models, sources, prompts.", required = true)
                    String section) {
        return switch (section) {
            case "rules" ->
                RulesView.of(config.snapshot().rules(), config.snapshot().profile());
            case "scoring-models" -> prompts.scoringModels();
            case "sources" -> sources.summaries();
            case "prompts" -> prompts.prompts();
            default ->
                throw new IllegalArgumentException(
                        "unknown section '" + section + "'; expected one of rules, scoring-models, sources, prompts");
        };
    }
}
