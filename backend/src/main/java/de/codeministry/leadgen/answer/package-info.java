/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * Asking a candidate model one of the pipeline's own bounded questions about one advert, and
 * handing back its answer beside the one already stored.
 *
 * <p>This is the server half of a measurement, not a stage: nothing here runs at night or
 * writes a row. {@code docs/samples/measure_routing.ts} calls it over a sample of adverts
 * the current configuration already answered and decides from the agreement and the latency
 * whether a smaller model can take a question over.
 *
 * <p>The model is refused unless the configuration names it under some key — the judge list
 * ({@code scoring} plus {@code scoring_options}), {@code content}, {@code fields} or
 * {@code extraction} — because the name arrives in a request and the endpoint behind it is
 * billed per token. The list is wider than the judge's own allowlist on purpose: the
 * incumbent of {@code blocks} or {@code fields} may be named under its stage's key only, and
 * it has to be measurable against its own stored answers without being offered to the
 * rescore. Each call takes one request from {@code llm.budget}.
 */
package de.codeministry.leadgen.answer;
