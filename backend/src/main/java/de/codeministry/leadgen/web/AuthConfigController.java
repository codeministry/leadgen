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
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.security.SecurityConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * How to log in, answered before anyone has.
 *
 * <p>The browser cannot be told at build time: nothing in this repository is wired into an
 * artifact, and the frontend has no build-time configuration at all. So it asks, and this
 * is the one endpoint that has to answer without a token — a bootstrap question cannot
 * require the thing it is bootstrapping.
 *
 * <p><b>Its own endpoint rather than a field on {@code /status}.</b> Status is behind the
 * wall in {@code oidc} mode and should stay there: it names the application and its
 * version, which is not a stranger's business. What is here is public by construction —
 * an issuer URL and a public client id both end up in the browser's address bar during the
 * flow, and neither is a secret in any OAuth sense.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AuthConfigController {

    private final ConfigRegistry config;

    @GetMapping("/auth-config")
    AuthConfig authConfig() {
        PipelineConfig.Security security = config.snapshot().application().security();
        if (!SecurityConfig.OIDC.equals(security.auth())) {
            return new AuthConfig(SecurityConfig.NONE, null, null);
        }
        Map<String, String> oidc = security.oidc() == null ? Map.of() : security.oidc();
        return new AuthConfig(SecurityConfig.OIDC, oidc.get("issuer"), oidc.get("client_id"));
    }
}
