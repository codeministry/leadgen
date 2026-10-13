/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.suggest;

/**
 * The data the empty chat's suggestions are found in has moved: a run ended or an application
 * changed status (ISC-529). Published by whoever writes {@code pipeline_run}'s end or an
 * {@code application_event}; {@link SuggestionWarmup} prepares the unpinned suggestions for the
 * next open once the write has committed.
 *
 * <p>A writer that forgets to publish costs only the warm-up: the next open misses, answers the
 * catalog at once and phrases in the background (ISC-528).
 *
 * @param writer who wrote, for the log
 */
public record SuggestionDataChanged(String writer) {}
