/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.event;

/**
 * The data the working set is read from has moved: a run ended or an application changed status.
 * Published by whoever writes {@code pipeline_run}'s end or an {@code application_event}; a
 * listener that needs the commit listens after it.
 *
 * <p>Today the one listener is the chat's suggestion warm-up (ISC-529). A writer that forgets to
 * publish costs only that warm-up: the next open misses, answers the catalog at once and phrases in
 * the background (ISC-528).
 *
 * @param writer who wrote, for the log
 */
public record DataChanged(String writer) {}
