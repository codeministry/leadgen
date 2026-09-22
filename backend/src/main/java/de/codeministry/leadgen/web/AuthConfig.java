/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

/**
 * What `GET /api/v1/auth-config` answers.
 *
 * @param mode {@code none} or {@code oidc}. Under {@code none} the other two are null and
 *     the browser does nothing about authentication at all.
 * @param issuer the realm's issuer URL, which the browser fetches its own discovery
 *     document from. Public: it is in the address bar during the redirect.
 * @param clientId the public client the browser identifies as. Public for the same reason,
 *     and it holds no secret — that is what makes PKCE necessary rather than optional.
 */
public record AuthConfig(String mode, String issuer, String clientId) {}
