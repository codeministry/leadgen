/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import de.codeministry.leadgen.config.ConfigProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one endpoint the skeleton owes: it lets the frontend prove that the proxy, the API
 * and the JSON contract line up. Everything real arrives with the pipeline stages.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class StatusController {

    // `spring.application.name` is Boot's key rather than ours, and one key is not worth a
    // second `@ConfigurationProperties` record, so the environment answers it directly.
    private final Environment environment;
    private final ConfigProperties config;

    @GetMapping("/status")
    AppStatus status() {
        return new AppStatus(environment.getRequiredProperty("spring.application.name"), config.version());
    }
}
