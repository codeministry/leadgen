/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.RulesView;
import de.codeministry.leadgen.config.SourceQueryService;
import de.codeministry.leadgen.config.SourceSummary;
import de.codeministry.leadgen.score.Judges;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The configuration as the screens read it. Read-only, and deliberately so: the four YAML
 * files are the source of truth and they are hot-reloaded, so editing them through an
 * endpoint would mean two ways to change the same thing disagreeing about which won.
 */
@RestController
@RequestMapping("/api")
class ConfigController {

    private final SourceQueryService sources;
    private final ConfigRegistry config;
    private final Judges judges;

    ConfigController(SourceQueryService sources, ConfigRegistry config, Judges judges) {
        this.sources = sources;
        this.config = config;
        this.judges = judges;
    }

    @GetMapping("/sources")
    List<SourceSummary> sources() {
        return sources.summaries();
    }

    @GetMapping("/rules")
    RulesView rules() {
        return RulesView.of(config.snapshot().rules());
    }

    /**
     * What the select beside the run button offers.
     *
     * <p>Read from {@code Judges} rather than from the snapshot directly, so the endpoint
     * and the allowlist that refuses a request cannot disagree: one list, one place.
     */
    @GetMapping("/scoring-models")
    ScoringModels scoringModels() {
        return ScoringModels.of(judges.choices());
    }
}
