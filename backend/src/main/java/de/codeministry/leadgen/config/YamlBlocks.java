/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.*;

import java.io.StringReader;
import java.util.List;
import java.util.Optional;

/**
 * Finds one item of a configuration file inside the file's own text.
 *
 * <p><b>The file's bytes, never the bound model.</b> A re-serialised {@code SourcesConfig} is
 * the resolved snapshot in disguise: every {@code ${IMAP_PASSWORD}} already replaced by what it
 * resolved to, and every comment gone. The comments are most of what makes this worth showing —
 * in the shipped {@code sources.yaml} the newsletter block runs to 59 lines, and the ones
 * explaining why progress is never read off seen/unseen, or how {@code multipart/alternative}
 * orders its parts, are the file's actual documentation.
 *
 * <p><b>{@code compose} and never {@code load}.</b> Composing builds the node graph and stops
 * there: nothing is constructed, nothing is resolved, and every node carries the line it came
 * from. Loading would hand back a {@code Map} that has forgotten where it was written.
 *
 * <p>The range is returned beside the text so the panel can print {@code lines 139–163} and a
 * reader can check the excerpt against {@code cat} rather than trusting it.
 */
public final class YamlBlocks {

    private YamlBlocks() {
    }

    /**
     * One item of a top-level sequence, addressed by its {@code id}.
     *
     * @param yaml    the file as it was read
     * @param section the top-level key holding the sequence, {@code sources} or {@code connections}
     * @param id      the item's {@code id} value
     */
    public static Optional<YamlBlock> item(String yaml, String section, String id) {
        if (yaml == null || yaml.isBlank() || id == null || id.isBlank()) {
            return Optional.empty();
        }
        Node root;
        try {
            root = new Yaml().compose(new StringReader(yaml));
        } catch (RuntimeException e) {
            // A file that does not parse is a real state: the loader keeps the last good
            // snapshot and logs, so the application runs on configuration the file no longer
            // contains. Nothing to show, and nothing worth failing a request over.
            return Optional.empty();
        }
        if (!(root instanceof MappingNode document)) {
            return Optional.empty();
        }
        List<String> lines = yaml.lines().toList();
        for (NodeTuple tuple : document.getValue()) {
            if (!section.equals(scalarValue(tuple.getKeyNode()))
                || !(tuple.getValueNode() instanceof SequenceNode sequence)) {
                continue;
            }
            List<Node> elements = sequence.getValue();
            for (int i = 0; i < elements.size(); i++) {
                if (elements.get(i) instanceof MappingNode item && id.equals(childValue(item, "id"))) {
                    // Bounded by the next item's first line rather than by this one's end
                    // mark — see `cut`.
                    int limit = i + 1 < elements.size()
                        ? elements.get(i + 1).getStartMark().getLine()
                        : lines.size();
                    return Optional.of(cut(lines, item, limit));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The block's own lines, comments included.
     *
     * <p><b>The end mark is not usable as the last line.</b> SnakeYAML points it at the first
     * token of whatever follows, and for a sequence of blocks that is the next item — measured
     * on the shipped file, the newsletter block reported its end on the line reading
     * {@code - id: sample-portal-feed}, past a comment belonging to that block. Checking the
     * mark's column does not save it either, because that token starts at the dash's column
     * and not at zero. So the bound is the next item's <i>start</i>, which is exact, and the
     * last line is the last one inside it that carries content.
     */
    private static YamlBlock cut(List<String> lines, MappingNode item, int limit) {
        int first = item.getStartMark().getLine();
        int indent = item.getStartMark().getColumn();
        int last = first;
        for (int i = first + 1; i < limit && i < lines.size(); i++) {
            String line = lines.get(i);
            // Neither advances the end: a comment at the bottom of a block introduces the next
            // one far more often than it closes this one, and a blank line belongs to nobody.
            if (line.isBlank() || line.strip().startsWith("#")) {
                continue;
            }
            // Dedented back out of the item: a following top-level key, which only the last
            // item in a sequence can run into.
            if (indentOf(line) < indent) {
                break;
            }
            last = i;
        }
        // A block's comments sit above it and are the half worth reading. Contiguous only: a
        // blank line ends the run, which is what keeps a section header like
        // `# --- Sources ---` out of the first block that follows it.
        while (first > 0 && lines.get(first - 1).strip().startsWith("#")) {
            first--;
        }
        String text = String.join("\n", lines.subList(first, last + 1));
        return new YamlBlock(text, first + 1, last + 1);
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static String childValue(MappingNode mapping, String key) {
        for (NodeTuple tuple : mapping.getValue()) {
            if (key.equals(scalarValue(tuple.getKeyNode()))) {
                return scalarValue(tuple.getValueNode());
            }
        }
        return null;
    }

    private static String scalarValue(Node node) {
        return node instanceof ScalarNode scalar ? scalar.getValue() : null;
    }
}
