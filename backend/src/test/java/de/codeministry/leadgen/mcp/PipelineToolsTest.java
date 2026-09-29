/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.analytics.AnalyticsQueryService;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The funnel tool's descriptions name the analytics sections from constants, since an annotation
 * takes nothing else; a section added to {@link AnalyticsQueryService#SECTIONS} fails here until
 * the descriptions a model reads name it too.
 */
class PipelineToolsTest {

    @Test
    void namesEverySectionTheAnalyticsServiceAnswers() {
        assertThat(PipelineTools.SECTION_NAMES).isEqualTo(String.join(", ", AnalyticsQueryService.SECTIONS));
        assertThat(PipelineTools.QUOTED_SECTION_NAMES)
                .isEqualTo(AnalyticsQueryService.SECTIONS.stream()
                        .map(name -> "'" + name + "'")
                        .collect(Collectors.joining(", ")));
    }
}
