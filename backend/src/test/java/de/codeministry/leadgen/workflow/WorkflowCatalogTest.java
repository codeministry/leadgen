/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.workflow.WorkflowCatalog.KeyRef;
import de.codeministry.leadgen.workflow.WorkflowCatalog.StageEntry;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The catalog on its own: no Spring, no files. What it pins is the shape the service builds on —
 * the phase order of {@code docs/BACKEND-FLOWS.md} §1, the stage order {@code IngestOrderTest}
 * pins, and a key path that no two stages can both claim.
 */
class WorkflowCatalogTest {

    @Test
    void everyKnockoutKeyIsFiledUnderFilter() {
        // A knockout path that another stage covers first would be named by the knockout but shown
        // under that stage, so the screen could not put the rule beside the knockout that applies it.
        for (var knockout : WorkflowCatalog.knockouts()) {
            for (KeyRef key : knockout.keys()) {
                assertThat(WorkflowCatalog.ownerOf(key.file(), key.path()))
                        .as("%s reads %s", knockout.id(), key.path())
                        .contains(WorkflowCatalog.FILTER);
            }
        }
    }

    @Test
    void phasesAreTheFiveSubgraphsInRunOrder() {
        assertThat(WorkflowCatalog.phaseIds()).containsExactly("read", "sort", "understand", "judge", "hand");
    }

    @Test
    void stagesAreTheElevenTimedNamesInRunOrder() {
        assertThat(WorkflowCatalog.stageIds())
                .containsExactly(
                        "DEDUPE",
                        "FILTER",
                        "ARCHIVE",
                        "ENRICH",
                        "CONTENT",
                        "FIELDS",
                        "SCORE",
                        "RETRIEVAL",
                        "OPEN",
                        "PACKAGE",
                        "DIGEST");
    }

    @Test
    void eachPhaseHoldsItsStagesAndReadHoldsOnlyTheIngestEntries() {
        assertThat(WorkflowCatalog.stagesOf("read")).isEmpty();
        assertThat(ids(WorkflowCatalog.stagesOf("sort"))).containsExactly("DEDUPE", "FILTER", "ARCHIVE");
        assertThat(ids(WorkflowCatalog.stagesOf("understand"))).containsExactly("ENRICH", "CONTENT", "FIELDS");
        assertThat(ids(WorkflowCatalog.stagesOf("judge"))).containsExactly("SCORE", "RETRIEVAL");
        assertThat(ids(WorkflowCatalog.stagesOf("hand"))).containsExactly("OPEN", "PACKAGE", "DIGEST");
        assertThat(WorkflowCatalog.INGEST.phase()).isEqualTo("read");
    }

    @Test
    void costClassesFollowTheMermaidNodeClasses() {
        assertThat(WorkflowCatalog.INGEST.costClasses()).containsExactly(WorkflowView.COST_NETWORK);
        assertThat(cost("DEDUPE")).containsExactly(WorkflowView.COST_MODEL);
        assertThat(cost("FILTER")).containsExactly(WorkflowView.COST_FREE);
        assertThat(cost("ARCHIVE")).containsExactly(WorkflowView.COST_FREE);
        assertThat(cost("ENRICH")).containsExactly(WorkflowView.COST_NETWORK);
        assertThat(cost("CONTENT")).containsExactly(WorkflowView.COST_MODEL);
        assertThat(cost("FIELDS")).containsExactly(WorkflowView.COST_MODEL);
        assertThat(cost("SCORE")).containsExactly(WorkflowView.COST_MODEL);
        assertThat(cost("RETRIEVAL")).containsExactly(WorkflowView.COST_MODEL);
        assertThat(cost("OPEN")).containsExactly(WorkflowView.COST_FREE);
        assertThat(cost("PACKAGE")).containsExactly(WorkflowView.COST_FILE);
        assertThat(cost("DIGEST")).containsExactly(WorkflowView.COST_FILE);
    }

    @Test
    void promptIdsAreThePromptViewIdsAndSitOnTheStagesThatSendThem() {
        assertThat(WorkflowCatalog.INGEST.promptId()).isEqualTo("extraction");
        assertThat(WorkflowCatalog.stage("CONTENT").orElseThrow().promptId()).isEqualTo("content");
        assertThat(WorkflowCatalog.stage("FIELDS").orElseThrow().promptId()).isEqualTo("fields");
        assertThat(WorkflowCatalog.stage("SCORE").orElseThrow().promptId()).isEqualTo("scoring");
        // ISC-326: the letter is drafted at PACKAGE, so that is where its prompt is shown.
        assertThat(WorkflowCatalog.stage("PACKAGE").orElseThrow().promptId()).isEqualTo("writing");
        List<String> withPrompt = new ArrayList<>();
        for (StageEntry stage : WorkflowCatalog.stages()) {
            if (stage.promptId() != null) {
                withPrompt.add(stage.id());
            }
        }
        assertThat(withPrompt).containsExactly("CONTENT", "FIELDS", "SCORE", "PACKAGE");
    }

    @Test
    void everyKeyIsQualifiedByOneOfTheThreeFilesAndHoldsNoValue() {
        Set<String> files = Set.of(
                WorkflowCatalog.FILE_PIPELINE, WorkflowCatalog.FILE_MATCHING_RULES, WorkflowCatalog.FILE_SKILL_PROFILE);
        for (KeyRef key : allKeys()) {
            assertThat(files).contains(key.file());
            assertThat(key.path()).matches("[a-z_]+(\\[\\])?(\\.[a-z_]+(\\[\\])?)*");
        }
    }

    @Test
    void noKeyIsClaimedTwiceNorCoveredByAnotherStagesPrefix() {
        List<KeyRef> keys = allKeys();
        for (int i = 0; i < keys.size(); i++) {
            for (int j = 0; j < keys.size(); j++) {
                if (i != j) {
                    KeyRef a = keys.get(i);
                    KeyRef b = keys.get(j);
                    assertThat(a.covers(b.file(), b.path()))
                            .as("%s covers %s", a, b)
                            .isFalse();
                }
            }
        }
    }

    @Test
    void aPrefixCoversItsSubtreeAndNothingThatMerelySharesItsSpelling() {
        KeyRef rules = new KeyRef(WorkflowCatalog.FILE_PIPELINE, "content.rules");
        assertThat(rules.covers(WorkflowCatalog.FILE_PIPELINE, "content.rules")).isTrue();
        assertThat(rules.covers(WorkflowCatalog.FILE_PIPELINE, "content.rules[].matches"))
                .isTrue();
        assertThat(rules.covers(WorkflowCatalog.FILE_PIPELINE, "content.rules_extra"))
                .isFalse();
        assertThat(rules.covers(WorkflowCatalog.FILE_MATCHING_RULES, "content.rules[].matches"))
                .isFalse();
        KeyRef fetch = new KeyRef(WorkflowCatalog.FILE_PIPELINE, "enrichment.fetch");
        assertThat(fetch.covers(WorkflowCatalog.FILE_PIPELINE, "enrichment.fetch.timeout"))
                .isTrue();
    }

    @Test
    void theOwnerOfALeafIsFoundAcrossFilesAndStages() {
        assertThat(WorkflowCatalog.ownerOf(WorkflowCatalog.FILE_PIPELINE, "llm.models.extraction"))
                .contains("INGEST");
        assertThat(WorkflowCatalog.ownerOf(WorkflowCatalog.FILE_PIPELINE, "enrichment.fetch.cache_ttl"))
                .contains("ENRICH");
        assertThat(WorkflowCatalog.ownerOf(WorkflowCatalog.FILE_SKILL_PROFILE, "core[].skill"))
                .contains("FILTER");
        assertThat(WorkflowCatalog.ownerOf(WorkflowCatalog.FILE_SKILL_PROFILE, "core[].weight"))
                .contains("SCORE");
        assertThat(WorkflowCatalog.ownerOf(WorkflowCatalog.FILE_MATCHING_RULES, "hard_filters.freshness.max_age_days"))
                .contains("ARCHIVE");
        assertThat(WorkflowCatalog.ownerOf(WorkflowCatalog.FILE_MATCHING_RULES, "follow_up.after_days"))
                .isEmpty();
    }

    @Test
    void theModelWidthAndPerStageModelKeysAreFiledWhereTheyFirstTakeEffect() {
        // ISC-373: the screen names the key each stage reads, so a key the stage reads but the
        // catalog does not file lands in the unread group and the stage looks unconfigurable.
        assertThat(WorkflowCatalog.ownerOf(WorkflowCatalog.FILE_PIPELINE, "llm.models.content"))
                .contains("CONTENT");
        assertThat(WorkflowCatalog.ownerOf(WorkflowCatalog.FILE_PIPELINE, "llm.models.fields"))
                .contains("FIELDS");
        // Read by the embedding batches of DEDUPE first, then RETRIEVAL, CONTENT, FIELDS and the
        // synchronous SCORE; filed once, under the first of them in run order.
        assertThat(WorkflowCatalog.ownerOf(WorkflowCatalog.FILE_PIPELINE, "llm.concurrency"))
                .contains("DEDUPE");
        assertThat(WorkflowCatalog.ownerOf(WorkflowCatalog.FILE_PIPELINE, "enrichment.fetch.concurrency"))
                .contains("ENRICH");
    }

    @Test
    void everyModelKeyTheShippedPipelineDeclaresIsFiledUnderAStage() throws IOException {
        // A new `llm.models.*` key is a new model a stage asks, so it is never "read by nothing".
        Map<String, Object> pipeline;
        try (InputStream in = WorkflowCatalogTest.class.getResourceAsStream("/leadgen/pipeline.yaml")) {
            pipeline = new Yaml().load(in);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> llm = (Map<String, Object>) pipeline.get("llm");
        @SuppressWarnings("unchecked")
        Map<String, Object> models = (Map<String, Object>) llm.get("models");
        assertThat(models).isNotEmpty();
        for (String name : models.keySet()) {
            assertThat(WorkflowCatalog.ownerOf(WorkflowCatalog.FILE_PIPELINE, "llm.models." + name))
                    .as("llm.models.%s", name)
                    .isPresent();
        }
    }

    private static List<KeyRef> allKeys() {
        List<KeyRef> keys = new ArrayList<>(WorkflowCatalog.INGEST.keys());
        WorkflowCatalog.stages().forEach(stage -> keys.addAll(stage.keys()));
        return keys;
    }

    private static List<String> cost(String id) {
        return WorkflowCatalog.stage(id).orElseThrow().costClasses();
    }

    private static List<String> ids(List<StageEntry> stages) {
        return stages.stream().map(StageEntry::id).toList();
    }
}
