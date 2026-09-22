/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.config.ConfigFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The working notes are split so that what is loaded on every turn stays small, and this
 * reads the repository to keep it that way.
 *
 * <p>It is enforced rather than remembered because the failure it corrects was measured:
 * the root {@code CLAUDE.md} went from 20,641 characters on 2026-09-01 to 166,477 two
 * weeks later, one decision at a time, each of them worth keeping. Nothing about that
 * growth looks wrong while it happens — every commit adds a paragraph somebody had to pay
 * for — which is exactly why review does not catch it and a number does.
 *
 * <p>The budgets are not aspirations. A rule is one or two lines, so the headroom in each
 * of them is a couple of dozen new rules; reasoning is what does not fit, and reasoning has
 * its own files in {@code docs/decisions/}. When one of these fails, the answer is almost
 * never a bigger number: it is a section whose paragraphs belong in a decision document,
 * the way the order of work and the six pipeline stages already moved.
 */
class WorkingNotesStaySmallTest {

    /**
     * What each file may cost, in characters.
     *
     * <p>The root file is the one loaded into every session whatever the work is. The two
     * nested files are loaded only when a file in their tree is read, which is why they
     * are allowed to be roughly half of it each rather than a share of it.
     */
    private static final Map<String, Integer> BUDGETS = new LinkedHashMap<>(Map.of(
            "CLAUDE.md", 18_000,
            "backend/CLAUDE.md", 12_000,
            "frontend/CLAUDE.md", 12_000));

    /**
     * A reference to a decision document, with or without the {@code docs/} prefix — the
     * inventory tree in the root notes writes the short form because the column is narrow.
     *
     * <p>Deliberately anchored on the directory name: {@code passwd.md} appears in
     * {@code pipeline-ingest.md} as the example of a sanitised upload name, and a pattern
     * matching any bare {@code *.md} would read it as a broken link.
     */
    private static final Pattern DECISION_REFERENCE = Pattern.compile("(?:docs/)?decisions/([a-z0-9-]+\\.md)");

    private static final Pattern NESTED_NOTES = Pattern.compile("\\b((?:backend|frontend)/CLAUDE\\.md)\\b");

    private static final Path ROOT = ConfigFixtures.repositoryRoot();

    @Test
    void everyInstructionFileStaysInsideItsBudget() {
        var offenders = new ArrayList<String>();
        BUDGETS.forEach((name, budget) -> {
            int size = read(ROOT.resolve(name)).length();
            if (size > budget) {
                offenders.add(
                        "%s is %,d characters, %,d over its budget of %,d — move the reasoning into docs/decisions/"
                                .formatted(name, size, size - budget, budget));
            }
        });
        assertThat(offenders)
                .as("what is loaded on every turn has to stay readable")
                .isEmpty();
    }

    @Test
    void everyPathTheNotesNameExists() {
        var offenders = new ArrayList<String>();
        for (Path file : markdownFiles()) {
            String content = read(file);
            String where = ROOT.relativize(file).toString();
            collect(DECISION_REFERENCE, content, name -> {
                if (!Files.isRegularFile(ROOT.resolve("docs/decisions").resolve(name))) {
                    offenders.add(where + " points at docs/decisions/" + name + ", which does not exist");
                }
            });
            collect(NESTED_NOTES, content, path -> {
                if (!Files.isRegularFile(ROOT.resolve(path))) {
                    offenders.add(where + " points at " + path + ", which does not exist");
                }
            });
        }
        assertThat(offenders)
                .as("a routing table that points at nothing is worse than no routing table")
                .isEmpty();
    }

    @Test
    void everyDecisionDocumentIsReachableFromTheTable() {
        String table = sectionOf(read(ROOT.resolve("CLAUDE.md")), "## The decision records");
        var unreachable = new TreeSet<String>();
        try (Stream<Path> documents = Files.list(ROOT.resolve("docs/decisions"))) {
            documents
                    .map(document -> document.getFileName().toString())
                    .filter(name -> name.endsWith(".md"))
                    .filter(name -> !table.contains(name))
                    .forEach(unreachable::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(unreachable)
                .as("a document nothing links to is a document nobody opens — add it to the table in CLAUDE.md")
                .isEmpty();
    }

    /**
     * The section from its heading up to the next one, so a name further down does not count.
     */
    private static String sectionOf(String content, String heading) {
        int start = content.indexOf(heading);
        assertThat(start)
                .as("CLAUDE.md must still carry the heading '%s'", heading)
                .isNotNegative();
        int next = content.indexOf("\n## ", start + heading.length());
        return next < 0 ? content.substring(start) : content.substring(start, next);
    }

    private static void collect(Pattern pattern, String content, java.util.function.Consumer<String> onMatch) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            onMatch.accept(matcher.group(1));
        }
    }

    /**
     * The notes themselves, the documents behind them, and everything that links to either.
     */
    private static List<Path> markdownFiles() {
        var files = new ArrayList<Path>();
        BUDGETS.keySet().forEach(name -> files.add(ROOT.resolve(name)));
        files.add(ROOT.resolve("README.md"));
        for (String directory : List.of("docs", "docs/decisions")) {
            try (Stream<Path> found = Files.list(ROOT.resolve(directory))) {
                found.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().endsWith(".md"))
                        .forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
