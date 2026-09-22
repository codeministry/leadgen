/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Cutting one block out of a configuration file, against the file that actually ships.
 */
class YamlBlocksTest {

    private static final String SHIPPED =
            ConfigSource.fromClasspath(ConfigLoader.SOURCES_FILE).orElseThrow().content();

    @Test
    void findsASourceAndReportsWhereItIs() {
        var block = YamlBlocks.item(SHIPPED, "sources", "manual-inbox").orElseThrow();
        List<String> lines = SHIPPED.lines().toList();

        assertThat(block.text()).contains("- id: manual-inbox").contains("strategy: markdown-frontmatter");
        // The range is what makes the excerpt checkable against `cat` rather than trusted, so
        // it is asserted against the file rather than against itself.
        assertThat(lines.get(block.firstLine() - 1))
                .isEqualTo(block.text().lines().findFirst().orElseThrow());
        assertThat(block.lastLine() - block.firstLine() + 1)
                .isEqualTo((int) block.text().lines().count());
    }

    @Test
    void keepsTheCommentsAboveTheBlockBecauseTheyAreTheDocumentation() {
        var block = YamlBlocks.item(SHIPPED, "sources", "manual-inbox").orElseThrow();

        assertThat(block.text().lines().findFirst().orElseThrow().strip()).startsWith("#");
        assertThat(block.text()).contains("This is a `file` source and not a new mechanism");
    }

    @Test
    void stopsAtABlankLineSoASectionHeaderBelongsToNobody() {
        // `# --- Sources ---` is followed by a blank line and then the first block's own
        // comment. Swept up, every block would carry the heading of the section it is in.
        var block = YamlBlocks.item(SHIPPED, "sources", "sample-newsletter").orElseThrow();

        assertThat(block.text()).doesNotContain("--- Sources ---");
        assertThat(block.text()).contains("- id: sample-newsletter");
    }

    @Test
    void doesNotSweepUpTheNextBlocksComment() {
        var first = YamlBlocks.item(SHIPPED, "sources", "sample-newsletter").orElseThrow();
        var second = YamlBlocks.item(SHIPPED, "sources", "sample-portal-feed").orElseThrow();

        assertThat(first.lastLine()).isLessThan(second.firstLine());
        assertThat(first.text()).doesNotContain("- id: sample-portal-feed");
    }

    @Test
    void findsAConnectionTheSameWay() {
        var block = YamlBlocks.item(SHIPPED, "connections", "mailbox-primary").orElseThrow();

        assertThat(block.text()).contains("- id: mailbox-primary").contains("type: imap");
    }

    @Test
    void answersNothingForAnIdNobodyWrote() {
        assertThat(YamlBlocks.item(SHIPPED, "sources", "no-such-source")).isEmpty();
        assertThat(YamlBlocks.item(SHIPPED, "no-such-section", "manual-inbox")).isEmpty();
    }

    @Test
    void answersNothingForAFileThatDoesNotParse() {
        // A real state: a half-saved edit is refused by the loader, which keeps the last good
        // snapshot and logs. The application then runs on configuration the file no longer
        // contains, and there is nothing to show for it.
        assertThat(YamlBlocks.item("sources:\n  - id: one\n   bad indent: [\n", "sources", "one"))
                .isEmpty();
        assertThat(YamlBlocks.item("", "sources", "one")).isEmpty();
    }
}
