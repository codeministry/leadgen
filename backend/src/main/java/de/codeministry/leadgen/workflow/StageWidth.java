/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.workflow;

/**
 * How many adverts a stage works on at once, and the key that sets it.
 *
 * <p>Stated at every width, one included: "one at a time" is a setting a person may want to
 * change, and a screen that only speaks up above one hides the key that would change it.
 *
 * @param key   the dotted key, {@code llm.concurrency} or {@code enrichment.fetch.concurrency}
 * @param value the resolved width, the key's default when the file leaves it out
 */
public record StageWidth(String key, int value) {}
