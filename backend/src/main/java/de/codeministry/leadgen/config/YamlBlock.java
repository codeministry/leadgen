/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

/**
 * A piece of a configuration file, as it is written in it.
 *
 * @param text      the lines themselves, comments included, secrets already masked
 * @param firstLine the first line's number in the file, counting from one, so the panel can
 *                  print the range and the excerpt stays checkable against the file
 * @param lastLine  the last line's number, inclusive
 */
public record YamlBlock(String text, int firstLine, int lastLine) {}
