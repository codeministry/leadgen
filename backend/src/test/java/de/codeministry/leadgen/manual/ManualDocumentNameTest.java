/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.manual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The name rules on their own, without a Spring context: what a name may be and where it lands. */
class ManualDocumentNameTest {

    @Test
    void refusesANameLongerThanAFileSystemTakesBeforeAnyPatternReadsIt() {
        String longest = "a".repeat(252) + ".md";
        assertThat(ManualDocumentName.sanitize(longest)).hasSize(96 + 3);

        assertThatThrownBy(() -> ManualDocumentName.sanitize("a" + longest))
                .isInstanceOf(ManualDocumentName.Rejected.class)
                .hasMessageContaining("at most 255");
        // The path variable that names a pending document is bounded only by the request header.
        assertThatThrownBy(() -> ManualDocumentName.sanitize("a" + "-".repeat(8000) + "a.md"))
                .isInstanceOf(ManualDocumentName.Rejected.class)
                .hasMessageContaining("at most 4096");
    }

    @Test
    void acceptsAShortNameSentWithALongLocalPath() {
        String path = "C:\\Users\\" + "deep\\".repeat(60) + "offer.md";

        assertThat(path.length()).isGreaterThan(255);
        assertThat(ManualDocumentName.sanitize(path)).isEqualTo("offer.md");
    }

    @Test
    void dropsTrailingHyphensAndDotsButKeepsTheOnesInside() {
        assertThat(ManualDocumentName.sanitize("kafka remote--..-.md")).isEqualTo("kafka-remote.md");
        assertThat(ManualDocumentName.sanitize("a-" + "-.".repeat(20) + "b.md"))
                .startsWith("a--.")
                .endsWith("-.b.md");
        // Cut to 96 inside a run of separators: the cut does not leave any at the end.
        assertThat(ManualDocumentName.sanitize("a" + "-.".repeat(100) + "b.md")).isEqualTo("a.md");
        assertThat(ManualDocumentName.sanitize("--..md")).isEqualTo("offer.md");
    }

    @Test
    void resolvesOnlyToAFileDirectlyInsideTheDirectory() {
        Path inbox = Path.of("/srv/leadgen/inbox/../inbox");

        assertThat(ManualDocumentName.resolve(inbox, "../../etc/passwd.md"))
                .isEqualTo(Path.of("/srv/leadgen/inbox/passwd.md"));
        assertThat(ManualDocumentName.resolve(inbox, "..\\..\\windows\\notes.md")
                        .getParent())
                .isEqualTo(Path.of("/srv/leadgen/inbox"));
    }
}
