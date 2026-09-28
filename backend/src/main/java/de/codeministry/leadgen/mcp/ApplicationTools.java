/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.mcp;

import de.codeministry.leadgen.application.ApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** The application board as an MCP tool, on the same {@link ApplicationService} the pipeline screen reads. */
@Component
@RequiredArgsConstructor
public class ApplicationTools {

    private final ApplicationService applications;

    @McpTool(
            name = "leadgen_list_applications",
            description = "The application board: every offer that moved past the shortlist, with its"
                    + " status, lane, score and whether a package was built for it. Note that"
                    + " the package itself is a zip download from leadgen's own UI and is"
                    + " deliberately not reachable through this server.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public ApplicationList listApplications(
            @McpToolParam(
                            description = "Only applications in this status, e.g. 'APPLIED'. Omit for all.",
                            required = false)
                    String status,
            @McpToolParam(description = "Maximum rows to return, 1-50. Default 50.", required = false) Integer limit) {
        var rows = applications.board().stream()
                .filter(view -> status == null
                        || status.isBlank()
                        || status.equalsIgnoreCase(view.status().name()))
                .map(ApplicationRow::of)
                .toList();
        int cap = Rows.clamp(limit, Rows.MAX);
        var page = rows.size() > cap ? rows.subList(0, cap) : rows;
        return new ApplicationList(page, page.size(), rows.size(), rows.size() > cap ? Boolean.TRUE : null);
    }
}
