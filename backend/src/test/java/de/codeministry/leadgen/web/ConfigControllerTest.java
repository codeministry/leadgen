/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.SourceDetail;
import de.codeministry.leadgen.config.SourceDetailService;
import de.codeministry.leadgen.config.SourceQueryService;
import de.codeministry.leadgen.config.SourceTrend;
import de.codeministry.leadgen.config.SourcesView;
import de.codeministry.leadgen.config.YamlBlock;
import de.codeministry.leadgen.score.Judges;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/**
 * The edge of the one endpoint on this controller that serves file text.
 */
@WebMvcTest(ConfigController.class)
class ConfigControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private SourceQueryService sources;

    @MockitoBean
    private SourceDetailService details;

    @MockitoBean
    private ConfigRegistry config;

    @MockitoBean
    private Judges judges;

    @Test
    void namesTheFileOnceRatherThanOnEveryRow() {
        // The layer is one probe for the whole file. As a column it was a badge per row
        // asserting something that cannot differ between two rows.
        given(sources.summaries()).willReturn(new SourcesView("sources.yaml", "config-dir", List.of()));

        assertThat(mvc.get().uri("/api/sources"))
            .hasStatusOk()
            .bodyJson()
            .isEqualTo("{\"file\":\"sources.yaml\",\"layer\":\"config-dir\",\"sources\":[]}");
    }

    @Test
    void answersNotFoundForASourceNobodyConfigured() {
        // Told apart from a source with nothing to show: this is a typed link, or a source
        // taken out of the file since the page was loaded.
        given(details.detail(eq("no-such-source"), anyInt())).willReturn(Optional.empty());

        assertThat(mvc.get().uri("/api/sources/no-such-source")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void handsTheSegmentOverAsANameAndNeverAsAPath() {
        // Whatever arrives in the segment is a lookup key: it is compared against the ids in
        // the configuration snapshot and matches nothing, which is a 404. The controller does
        // not decode it, does not normalise it, and never joins it to a directory — a request
        // parameter that reaches the filesystem is the shape of every directory traversal.
        //
        // MockMvc hands the segment over exactly as written rather than percent-decoding it the
        // way a container would, so the assertion names the raw form. The point survives either
        // spelling: both are names, and neither is a path.
        given(details.detail(any(), anyInt())).willReturn(Optional.empty());

        assertThat(mvc.get().uri("/api/sources/{id}", "..%2F..%2Fetc%2Fpasswd"))
            .hasStatus(HttpStatus.NOT_FOUND);

        then(details).should().detail("..%2F..%2Fetc%2Fpasswd", 0);
    }

    @Test
    void asksForAsManyRunsAsTheRequestNamed() {
        given(details.detail(eq("manual-inbox"), eq(7))).willReturn(Optional.of(detail()));

        assertThat(mvc.get().uri("/api/sources/manual-inbox").param("runs", "7")).hasStatusOk();

        then(details).should().detail("manual-inbox", 7);
    }

    private static SourceDetail detail() {
        return new SourceDetail(
            "manual-inbox",
            "file",
            true,
            new SourceDetail.ConfigFile("sources.yaml", "config-dir", "/config/sources.yaml"),
            new YamlBlock("- id: manual-inbox\n", 139, 163),
            null,
            List.of(),
            new SourceTrend(null, null, null, null, null, false));
    }
}
