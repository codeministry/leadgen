/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.content;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits an ad's Markdown into the blocks a decision is made about.
 *
 * <p>The unit is a block and not a sentence, because a decision has to be reversible in
 * place: a reader who suspects too much was hidden clicks once and gets back something they
 * can recognise. Sentence-level marking would need offsets into a string that normalisation
 * moves, which is the design this deliberately does not have.
 *
 * <p>Blank lines are the primary boundary, as they are in Markdown itself. Two more are
 * forced, because Markdown allows them without one: a heading always starts a block, and so
 * does the first item of a list. Without the second rule the sample corpus produces
 * "Apply now / Save to watchlist / * Print / * Report" as a single paragraph, because
 * flexmark writes those as hard breaks inside one.
 *
 * <p><b>The honest limit:</b> a block that is nine parts boilerplate and one part per-offer
 * text is one block, so it never repeats and never gets a cache hit. On the sample source
 * that is the report dialog with the ad's own taxonomy line glued onto its end — which is
 * why the {@code full_text} selector matters as much as this does. Narrowing what enters the
 * text is cheaper than teaching this to guess where a paragraph changes subject.
 */
public final class MarkdownBlocks {

    private static final Pattern HEADING = Pattern.compile("^ {0,3}#{1,6}\\s");
    private static final Pattern LIST_ITEM = Pattern.compile("^ {0,3}(?:[-*+]\\s|\\d{1,9}[.)]\\s)");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})");
    private static final Pattern BREAK = Pattern.compile("^ {0,3}(?:-{3,}|\\*{3,}|_{3,})\\s*$");

    private MarkdownBlocks() {
    }

    /**
     * The blocks of {@code markdown}, in order, with no empty ones.
     */
    public static List<String> split(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }

        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String openFence = null;
        boolean previousWasListItem = false;

        for (String line : markdown.split("\\R", -1)) {
            if (openFence != null) {
                current.append(line).append('\n');
                if (line.stripTrailing().startsWith(openFence)) {
                    openFence = null;
                }
                continue;
            }

            var fence = FENCE.matcher(line);
            if (fence.find()) {
                // A fenced block is one block whatever is inside it, blank lines included:
                // an ad that pastes a stack listing must not be cut in the middle of it.
                flush(blocks, current);
                current.append(line).append('\n');
                openFence = fence.group(1);
                previousWasListItem = false;
                continue;
            }

            if (line.isBlank() || HEADING.matcher(line).find() || BREAK.matcher(line).find()) {
                flush(blocks, current);
                previousWasListItem = false;
                if (!line.isBlank()) {
                    current.append(line).append('\n');
                }
                continue;
            }

            boolean isListItem = LIST_ITEM.matcher(line).find();
            if (isListItem && !previousWasListItem) {
                flush(blocks, current);
            }
            previousWasListItem = isListItem;
            current.append(line).append('\n');
        }

        flush(blocks, current);
        return List.copyOf(blocks);
    }

    private static void flush(List<String> blocks, StringBuilder current) {
        String block = current.toString().strip();
        current.setLength(0);
        if (!block.isEmpty()) {
            blocks.add(block);
        }
    }
}
