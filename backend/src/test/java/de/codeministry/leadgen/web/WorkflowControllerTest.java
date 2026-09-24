/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigLoader;
import de.codeministry.leadgen.config.ConfigProperties;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.filter.FilterStage;
import de.codeministry.leadgen.ingest.IngestService;
import de.codeministry.leadgen.workflow.WorkflowService;
import de.codeministry.leadgen.workflow.WorkflowView;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The edge of {@code GET /api/v1/workflow}, over the shipped defaults with the sample newsletter
 * switched on beside the manual inbox — the same two-source setup {@code WorkflowServiceTest}
 * uses, built here so the assertions run against the actual JSON the browser receives rather than
 * against {@link WorkflowView} objects.
 *
 * <p>In {@code de.codeministry.leadgen.web} rather than the {@code tasks.md} path under
 * {@code workflow/}: {@code WorkflowController} is package-private, like {@code ConfigController}
 * beside it, and {@code @WebMvcTest} needs the test in the controller's own package to construct
 * it.
 */
@WebMvcTest(WorkflowController.class)
class WorkflowControllerTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    /** What an enabled IMAP source needs before the loader accepts it, as in {@code WorkflowServiceTest}. */
    private static final Map<String, String> IMAP =
            Map.of("IMAP_HOST", "imap.example.org", "IMAP_USER", "someone", "IMAP_PASSWORD", "not-a-secret");

    private static final Set<String> KNOWN_COST_CLASSES =
            Set.of(WorkflowView.COST_FREE, WorkflowView.COST_MODEL, WorkflowView.COST_NETWORK, WorkflowView.COST_FILE);

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private WorkflowService service;

    @TempDir
    Path configDir;

    @Test
    void answersThePhasesTheIngestEntriesAndTheElevenTimedStagesInOrder() throws IOException {
        // Built as a separate statement rather than inline: nesting a second mock's
        // when(...).thenReturn(...) inside the argument list of given(service.view()) confuses
        // Mockito's mocking progress between the two mocks and throws UnfinishedStubbingException.
        WorkflowView view = workflowOverShippedDefaultsWithTwoSources();
        given(service.view()).willReturn(view);

        MvcTestResult result = mvc.get().uri("/api/v1/workflow").exchange();
        assertThat(result).hasStatusOk();
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        JsonNode phases = body.path("phases");

        // Five phases, in run order.
        assertThat(ids(phases)).containsExactly("read", "sort", "understand", "judge", "hand");

        // Phase 0 (read): one ingest entry per enabled source, in file order.
        JsonNode readStages = phases.get(0).path("stages");
        assertThat(ids(readStages)).containsExactly("INGEST sample-newsletter", "INGEST manual-inbox");
        for (JsonNode stage : readStages) {
            assertThat(stage.path("kind").asText()).isEqualTo(WorkflowView.KIND_INGEST);
        }

        // The eleven stages after the ingest entries, against the constant IngestService times,
        // never a copy of it.
        List<String> stageIdsAfterRead = new ArrayList<>();
        for (int i = 1; i < phases.size(); i++) {
            for (JsonNode stage : phases.get(i).path("stages")) {
                stageIdsAfterRead.add(stage.path("id").asText());
                assertThat(stage.path("kind").asText()).isEqualTo(WorkflowView.KIND_STAGE);
            }
        }
        assertThat(stageIdsAfterRead).containsExactlyElementsOf(IngestService.GLOBAL_STAGE_NAMES);

        // Every stage — ingest entries and timed stages alike — carries a non-empty cost class
        // drawn from the four known ones, and every setting carries key, value and file.
        for (JsonNode phase : phases) {
            for (JsonNode stage : phase.path("stages")) {
                JsonNode costClasses = stage.path("costClasses");
                assertThat(costClasses).isNotEmpty();
                for (JsonNode costClass : costClasses) {
                    assertThat(KNOWN_COST_CLASSES).contains(costClass.asText());
                }
                for (JsonNode setting : stage.path("settings")) {
                    assertThat(setting.path("key").asText()).isNotBlank();
                    assertThat(setting.hasNonNull("value")).isTrue();
                    assertThat(setting.path("file").asText()).isNotBlank();
                }
            }
        }
    }

    @Test
    void carriesTheSixKnockoutsOnFilterAndFilesTheRateFloorAndTheCountryAllowlistElsewhere() throws IOException {
        WorkflowView view = workflowOverShippedDefaultsWithTwoSources();
        given(service.view()).willReturn(view);

        MvcTestResult result = mvc.get().uri("/api/v1/workflow").exchange();
        assertThat(result).hasStatusOk();
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());

        JsonNode filter = stage(body, "FILTER");
        JsonNode knockouts = filter.path("knockouts");

        // One knockout per FilterStage value, in enum order, id in the analytics form: a new enum
        // value without a catalog entry fails here (and the catalog's exhaustive switch fails to
        // compile before that).
        List<String> expectedIds = Arrays.stream(FilterStage.values())
                .map(stage -> stage.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                .toList();
        assertThat(ids(knockouts)).containsExactlyElementsOf(expectedIds);

        Set<String> filterKeys = new HashSet<>();
        filter.path("settings")
                .forEach(setting -> filterKeys.add(setting.path("key").asText()));
        for (int i = 0; i < knockouts.size(); i++) {
            JsonNode knockout = knockouts.get(i);
            assertThat(knockout.path("description").asText()).isEqualTo(FilterStage.values()[i].description());
            List<String> keys = texts(knockout.path("keys"));
            assertThat(keys).as("keys of %s", knockout.path("id").asText()).isNotEmpty();
            // A knockout references the stage's settings; it never carries a key of its own.
            assertThat(filterKeys).containsAll(keys);
        }

        // NO_CORE_SKILL reads the skill profile's core list, names and aliases.
        JsonNode noCoreSkill = knockouts.get(FilterStage.NO_CORE_SKILL.ordinal());
        assertThat(texts(noCoreSkill.path("keys"))).contains("core[].skill", "core[].aliases");

        // Neither the rate floor nor the country allowlist is a knockout.
        List<String> allKnockoutKeys = new ArrayList<>();
        knockouts.forEach(knockout -> allKnockoutKeys.addAll(texts(knockout.path("keys"))));
        assertThat(allKnockoutKeys)
                .doesNotContain("hard_filters.rate.min_hourly_eur", "hard_filters.location.country_allowlist");

        // The rate floor is filed under SCORE, where it applies after enrichment.
        assertThat(keys(stage(body, "SCORE").path("settings"))).contains("hard_filters.rate.min_hourly_eur");
        assertThat(filterKeys).doesNotContain("hard_filters.rate.min_hourly_eur");

        // The country allowlist is read by nothing.
        assertThat(keys(body.path("unread"))).contains("hard_filters.location.country_allowlist");

        // No stage but FILTER carries knockouts.
        for (JsonNode phase : body.path("phases")) {
            for (JsonNode stage : phase.path("stages")) {
                if (!stage.path("id").asText().equals("FILTER")) {
                    assertThat(stage.hasNonNull("knockouts"))
                            .as("knockouts on %s", stage.path("id").asText())
                            .isFalse();
                }
            }
        }
    }

    @Test
    void answersNotFoundOnAnyOtherPath() {
        assertThat(mvc.get().uri("/api/v1/workflow/nope")).hasStatus4xxClientError();
    }

    private static List<String> ids(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> out.add(node.path("id").asText()));
        return out;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }

    private static List<String> keys(JsonNode settings) {
        List<String> out = new ArrayList<>();
        settings.forEach(setting -> out.add(setting.path("key").asText()));
        return out;
    }

    private static JsonNode stage(JsonNode body, String id) {
        for (JsonNode phase : body.path("phases")) {
            for (JsonNode stage : phase.path("stages")) {
                if (stage.path("id").asText().equals(id)) {
                    return stage;
                }
            }
        }
        throw new AssertionError("no stage " + id);
    }

    // --- the two-source workflow, built exactly as WorkflowServiceTest builds it -------------

    private WorkflowView workflowOverShippedDefaultsWithTwoSources() throws IOException {
        ConfigFixtures.materialize(configDir);
        replace(
                ConfigLoader.SOURCES_FILE,
                "id: sample-newsletter\n    enabled: false",
                "id: sample-newsletter\n    enabled: true");
        ConfigRegistry registry = mock(ConfigRegistry.class);
        var snapshot = ConfigFixtures.loaderFor(configDir, VALIDATOR, IMAP).load();
        when(registry.snapshot()).thenReturn(snapshot);
        return new WorkflowService(registry, new ConfigProperties(configDir.toString())).view();
    }

    private void replace(String file, String from, String to) throws IOException {
        Path path = configDir.resolve(file);
        String text = Files.readString(path, StandardCharsets.UTF_8);
        assertThat(text).as("fixture anchor in %s", file).contains(from);
        Files.writeString(path, text.replace(from, to), StandardCharsets.UTF_8);
    }
}
