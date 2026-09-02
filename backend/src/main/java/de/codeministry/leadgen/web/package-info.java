/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * The HTTP surface, and nothing else.
 *
 * <p>Controllers only. Every stage and every query service lives in its own package, so a
 * controller here is a few lines of routing over something that can be read and tested on its
 * own.
 *
 * <p>The write endpoints are unauthenticated, and deliberately so rather than by omission:
 * {@code security.auth} accepts exactly one value and is fatal on any other, because somebody
 * writing {@code basic} and believing the endpoints are protected is the worst failure
 * available. What stands in front of them is {@code server.address}, which defaults to
 * loopback.
 *
 * <p>A refusal carries its reason as plain text. "only .md documents are accepted" is
 * actionable; a bare 400 is a support request.
 *
 * <p>An argument that decides what gets bought is checked before the run starts, not where it
 * is used. Checked late, an unknown model name comes back 400 having already read the sources,
 * clustered the duplicates, applied the filter and fetched the surviving adverts.
 */
package de.codeministry.leadgen.web;
