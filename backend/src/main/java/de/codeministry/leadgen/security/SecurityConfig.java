/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.security;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.PipelineConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Who may call this API, decided by {@code security.auth} at startup.
 *
 * <p>Two modes, and only two. {@code none} is what shipped and stays the default: nothing
 * in front of the endpoints except {@code SERVER_ADDRESS}, which binds to {@code 127.0.0.1}
 * unless a deployment says otherwise. {@code oidc} makes this a resource server: every
 * request carries a bearer token, the token is verified against the issuer's own keys, and
 * nothing else is accepted.
 *
 * <p><b>Resource server and not a login.</b> The API has two callers and only one of them
 * is a browser: the SPA gets its token by Authorization Code with PKCE, and the MCP server
 * gets one by client credentials. A cookie session would serve the first and lock out the
 * second, so one mechanism serves both and this side stays stateless.
 *
 * <p><b>Why the mode is read here and not by a bean condition.</b> {@code processAot} runs
 * the application context at build time, so a {@code @ConditionalOnProperty} chain would be
 * decided when the image is built — with no operator environment present — and switching
 * the mode in the running container would change nothing at all, silently. Spring Boot's
 * own {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} auto-configuration has
 * exactly that shape, which is why the decoder below is built by hand instead.
 *
 * <p><b>The mode is read once, at startup.</b> `ConfigWatcher` hot-reloads the four
 * configuration files, and a filter chain is not rebuilt when it does. Changing
 * {@code security.auth} therefore takes a restart, and the startup log says which mode is
 * in force so the gap between the file and the process is visible rather than assumed.
 */
@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** The two values {@code security.auth} may take. `ConfigLoader` refuses anything else. */
    public static final String NONE = "none";

    public static final String OIDC = "oidc";

    static final String ISSUER = "issuer";
    static final String CLIENT_ID = "client_id";

    /**
     * Open in both modes, and each for its own reason. The container's liveness question
     * has no caller to authenticate and is asked before anything else is up. The
     * auth-config endpoint is how the browser learns where to log in, and a bootstrap
     * question cannot require the thing it bootstraps; what it answers is an issuer URL
     * and a public client id, both of which end up in the address bar anyway.
     */
    private static final String[] ALWAYS_OPEN = {
        "/actuator/health", "/actuator/health/**", "/actuator/info", "/api/v1/auth-config"
    };

    /**
     * A bean rather than a local, for two reasons that point the same way. A test can
     * replace it, which is the only way to exercise the {@code oidc} chain without standing
     * up a realm; and in {@code none} mode it is a decoder nobody asks, so the discovery
     * request is not made at all rather than made and ignored.
     */
    @Bean
    JwtDecoder jwtDecoder(ConfigRegistry config) {
        PipelineConfig.Security security = config.snapshot().application().security();
        if (!OIDC.equals(security.auth())) {
            return token -> {
                throw new IllegalStateException("security.auth is not 'oidc'; nothing here verifies a token");
            };
        }
        return discovered(security);
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ConfigRegistry config, JwtDecoder decoder) throws Exception {
        PipelineConfig.Security security = config.snapshot().application().security();
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (!OIDC.equals(security.auth())) {
            log.info(
                    "security.auth is '{}': every endpoint is open, and SERVER_ADDRESS is the only thing"
                            + " in front of the write paths",
                    security.auth());
            return http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                    .build();
        }

        String issuer = value(security, ISSUER);
        log.info("security.auth is 'oidc': bearer tokens verified against {}", issuer);
        return http.authorizeHttpRequests(requests -> requests.requestMatchers(ALWAYS_OPEN)
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(decoder)))
                .build();
    }

    /**
     * The decoder, built from the issuer's own discovery document.
     *
     * <p>Discovery is a request to the issuer at startup, so an unreachable Keycloak stops
     * the application rather than starting it with authentication that cannot work. That is
     * the intended trade: the alternative is a process that answers every request with a
     * 401 and looks like a broken token.
     *
     * <p><b>The audience is only checked when one is configured.</b> Keycloak puts the
     * client in {@code azp} by default and {@code aud} carries {@code account}, so a client
     * id checked against {@code aud} rejects every real token until an audience mapper is
     * added to the client. Leaving {@code OIDC_CLIENT_ID} empty means issuer and signature
     * only, which is the configuration that works out of the box; setting it is the
     * deliberate, stricter choice, and it needs the mapper on the Keycloak side.
     */
    private static JwtDecoder discovered(PipelineConfig.Security security) {
        String issuer = value(security, ISSUER);
        if (issuer == null || issuer.isBlank()) {
            // Unreachable while ConfigLoader does its job, said out loud for the day it does not.
            throw new IllegalStateException("security.auth is 'oidc' and security.oidc.issuer is empty");
        }
        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(JwtValidators.createDefaultWithIssuer(issuer));

        String clientId = value(security, CLIENT_ID);
        if (clientId != null && !clientId.isBlank()) {
            log.info("Tokens must also name '{}' in their audience", clientId);
            validators.add(new JwtClaimValidator<List<String>>(
                    "aud", audience -> audience != null && audience.contains(clientId)));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    private static String value(PipelineConfig.Security security, String key) {
        Map<String, String> oidc = security.oidc();
        return oidc == null ? null : oidc.get(key);
    }
}
