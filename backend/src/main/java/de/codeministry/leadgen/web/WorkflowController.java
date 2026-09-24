/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import de.codeministry.leadgen.workflow.WorkflowService;
import de.codeministry.leadgen.workflow.WorkflowView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pipeline as the rules screen shows it: phases, stages and the settings each one reads.
 *
 * <p>A separate endpoint rather than a wider {@code /rules}: the codeministry-mcp
 * pipeline-config tool reads {@code /rules} and {@code /prompts} as they stand today, and this
 * repository cannot see when a changed shape there breaks it (see {@code ConfigControllerTest}'s
 * shape tests, ISC-286). {@link WorkflowView} is a new response with nothing else depending on
 * its shape yet.
 *
 * <p>Read-only, like {@link ConfigController} beside it: the YAML files stay the source of
 * truth, and the mapping from key to stage lives in {@code WorkflowCatalog}, held against
 * {@code IngestService} and {@code FilterStage} by test rather than copied here.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class WorkflowController {

    private final WorkflowService service;

    @GetMapping("/workflow")
    WorkflowView workflow() {
        return service.view();
    }
}
