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
 * One source, opened: what defines it and what it has been doing.
 *
 * <p>One record and one request, because one disclosure is one click. Split in two, the panel
 * would carry two loading states and two failure modes for a single decision.
 *
 * <p>Nothing here is on {@link SourceSummary}. That list is refetched whenever a run finishes
 * and whenever the tab comes back to the front, and configuration text has no business on that
 * path for a panel most readers never open.
 *
 * @param id         the source, as {@code sources.yaml} names it
 * @param kind       whatever {@code type} that block declares
 * @param enabled    whether a run would read it
 * @param file       which file defines it, and from which of the two layers
 * @param block      that file's own lines for this source, secrets masked
 * @param connection the block of the connection it names, when it names one. An {@code imap}
 *                   source is half-defined by a block somewhere else in the file, and a reader
 *                   sent to go and find it will find the credentials rather than the settings.
 * @param runs       what it did, newest first, at most what was asked for
 * @param trend      when the numbers last moved, so the panel can say it in a sentence
 */
public record SourceDetail(
    String id,
    String kind,
    boolean enabled,
    ConfigFile file,
    YamlBlock block,
    YamlBlock connection,
    List<SourceRun> runs,
    SourceTrend trend) {

    /**
     * @param name   the file's own name, which is the same in both layers
     * @param layer  {@code default} for the copy inside the jar, {@code config-dir} for one
     *               outside it. One value for the whole file: the two layers override each
     *               other file by file and never key by key, which is exactly what the column
     *               this replaced was claiming to know per row.
     * @param origin where it was read from. A path on somebody's machine, so it belongs in a
     *               panel one deliberate click away and never in a header line that is in
     *               every screenshot.
     */
    public record ConfigFile(String name, String layer, String origin) {
    }
}
