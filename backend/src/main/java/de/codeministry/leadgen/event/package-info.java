/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * Facts a writer publishes and no consumer owns.
 *
 * <p>A record here says that something happened — a run ended, an application changed status —
 * and nothing about who listens. The writer imports this package and nothing of its listeners, so
 * a listener can be removed without touching a writer: the chat, which ISC-422 keeps removable,
 * prepares its suggestions from {@link de.codeministry.leadgen.event.DataChanged} without the
 * pipeline knowing the chat exists (ISC-541).
 */
package de.codeministry.leadgen.event;
