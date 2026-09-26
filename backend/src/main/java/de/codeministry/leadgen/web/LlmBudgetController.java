/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import de.codeministry.leadgen.llm.LlmBudget;
import de.codeministry.leadgen.llm.LlmBudgetView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The day's model allowance, read only. The run status sheet shows it beside the run, so a pass
 * that left scoring undone says whether the day was spent rather than leaving the reader to guess.
 */
@RestController
@RequestMapping("/api/v1/llm")
@RequiredArgsConstructor
class LlmBudgetController {

    private final LlmBudget budget;

    @GetMapping("/budget")
    LlmBudgetView budget() {
        return budget.view();
    }
}
