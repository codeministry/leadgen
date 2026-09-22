/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import java.util.List;

/**
 * What the sources screen reads: the file that defines the sources, and the sources.
 *
 * <p><b>An envelope, because the layer belongs to the file and not to a row.</b> It used to be
 * a column: one probe per request, stamped onto every source, rendering a badge that could not
 * differ between two rows because the two configuration layers override each other file by file
 * and never key by key. Same class as {@code remote.accept_unknown} — rendered, validated, and
 * unable to change anything. Stated once above the table it is simply true, and the table loses
 * a column, which is the only structural relief a seven-column table has at 390px.
 *
 * <p><b>The file's path is deliberately not here.</b> {@code /Users/&lt;name&gt;/…} is a personal
 * datum and this payload is behind every screenshot of the screen; it belongs in the panel that
 * one deliberate click opens, which is where {@link SourceDetail.ConfigFile} carries it.
 *
 * @param file  the file's own name, which is the same in both layers
 * @param layer {@code default} for the copy inside the jar, {@code config-dir} for one outside it
 */
public record SourcesView(String file, String layer, List<SourceSummary> sources) {}
