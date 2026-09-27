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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigLoader;
import de.codeministry.leadgen.config.ConfigProperties;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.Secrets;
import de.codeministry.leadgen.ingest.IngestService;
import de.codeministry.leadgen.workflow.WorkflowView.Phase;
import de.codeministry.leadgen.workflow.WorkflowView.Setting;
import de.codeministry.leadgen.workflow.WorkflowView.Stage;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The service over the shipped defaults, materialised as an external directory so a test can
 * override one file and watch the other layer win. No Spring context: the registry is a mock that
 * hands out a snapshot the real loader built from the same directory the service reads.
 */
class WorkflowServiceTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    /**
     * What an enabled IMAP source needs before the loader accepts it. Only {@code sources.yaml}
     * reads these, and the service never opens that file, so no value here can reach a view.
     */
    private static final Map<String, String> IMAP =
            Map.of("IMAP_HOST", "imap.example.org", "IMAP_USER", "someone", "IMAP_PASSWORD", "not-a-secret");

    @TempDir
    Path configDir;

    @Test
    void thePhasesAreTheFiveOfTheFlowInRunOrder() throws IOException {
        WorkflowView view = serviceWithTwoSources().view();

        assertThat(view.phases()).extracting(Phase::id).containsExactly("read", "sort", "understand", "judge", "hand");
    }

    @Test
    void oneIngestEntryPerEnabledSourceComesFirstInFileOrder() throws IOException {
        WorkflowView view = serviceWithTwoSources().view();

        List<Stage> read = view.phases().getFirst().stages();
        assertThat(read).extracting(Stage::id).containsExactly("INGEST sample-newsletter", "INGEST manual-inbox");
        assertThat(read).extracting(Stage::kind).containsOnly(WorkflowView.KIND_INGEST);
        assertThat(read).extracting(Stage::sourceId).containsExactly("sample-newsletter", "manual-inbox");
        // Only a source whose strategy or fallback is a model sends the extraction prompt.
        assertThat(read).extracting(Stage::promptId).containsExactly(null, "extraction");
    }

    @Test
    void theStagesAfterTheIngestEntriesAreTheNamesTheRunTimes() throws IOException {
        WorkflowView view = serviceWithTwoSources().view();

        List<String> stageIds = view.phases().stream()
                .skip(1)
                .flatMap(phase -> phase.stages().stream())
                .map(Stage::id)
                .toList();
        assertThat(stageIds).containsExactlyElementsOf(IngestService.GLOBAL_STAGE_NAMES);
        assertThat(view.phases().stream().skip(1).flatMap(phase -> phase.stages().stream()))
                .extracting(Stage::kind)
                .containsOnly(WorkflowView.KIND_STAGE);
    }

    @Test
    void theSharedIngestKeysSitOnTheFirstEntryOnly() throws IOException {
        WorkflowView view = serviceWithTwoSources().view();

        List<Stage> read = view.phases().getFirst().stages();
        assertThat(read.get(0).settings()).extracting(Setting::key).containsExactly("llm.models.extraction");
        assertThat(read.get(1).settings()).isEmpty();
    }

    @Test
    void withNoEnabledSourceTheReadPhaseIsEmptyAndItsKeysAreUnread() throws IOException {
        ConfigFixtures.materialize(configDir);
        replace(
                ConfigLoader.SOURCES_FILE,
                "id: manual-inbox\n    enabled: true",
                "id: manual-inbox\n    enabled: false");

        WorkflowView view = service().view();

        assertThat(view.phases().getFirst().stages()).isEmpty();
        assertThat(view.unread()).extracting(Setting::key).contains("llm.models.extraction");
    }

    @Test
    void everyLeafAppearsOnce() throws IOException {
        WorkflowView view = serviceWithTwoSources().view();

        List<String> filed = allSettings(view)
                .map(setting -> setting.file() + ":" + setting.key())
                .toList();
        assertThat(filed).doesNotHaveDuplicates();
        // Spot checks on the three path shapes: a scalar, a sequence of mappings, a scalar list.
        assertThat(filed)
                .contains(
                        "pipeline.yaml:llm.api_key",
                        "pipeline.yaml:content.rules[].kind",
                        "pipeline.yaml:digest.include",
                        "skill-profile.yaml:core[].skill",
                        "matching-rules.yaml:version");
    }

    @Test
    void keysAreFiledAtTheStageTheCatalogNames() throws IOException {
        WorkflowView view = serviceWithTwoSources().view();

        assertThat(stage(view, "SCORE").settings()).extracting(Setting::key).contains("llm.api_key", "version");
        assertThat(stage(view, "CONTENT").settings())
                .extracting(Setting::key)
                .containsExactly(
                        "content.enabled", "content.rules[].kind", "content.rules[].matches", "llm.models.content");
        assertThat(stage(view, "OPEN").settings()).isEmpty();
        // The pipeline's own `version` is read by nothing; the rules' is SCORE's.
        assertThat(view.unread())
                .extracting(setting -> setting.file() + ":" + setting.key())
                .contains("pipeline.yaml:version");
    }

    @Test
    void aSequenceOfMappingsIsOneLeafPerKeyWithEveryItemsValue() throws IOException {
        WorkflowView view = serviceWithTwoSources().view();

        Setting kinds = setting(view, "content.rules[].kind");
        assertThat(kinds.value()).startsWith("[CHROME, FORM, FORM, CHROME");
        Setting include = setting(view, "digest.include");
        assertThat(include.value()).isEqualTo("[shortlisted, review, follow_ups_due]");
    }

    @Test
    void aBarePlaceholderStaysVerbatim() throws IOException {
        WorkflowView view = serviceWithTwoSources().view();

        assertThat(setting(view, "llm.api_key").value()).isEqualTo("${LLM_API_KEY}");
        assertThat(setting(view, "llm.timeout").value()).isEqualTo("${LLM_TIMEOUT:PT120S}");
    }

    @Test
    void theSecurityBlockNeverAppears() throws IOException {
        ConfigFixtures.materialize(configDir);
        replace(
                ConfigLoader.PIPELINE_FILE,
                "    client_id: ${OIDC_CLIENT_ID:}",
                "    client_id: ${OIDC_CLIENT_ID:}\n    client_secret: oidc-literal-secret");

        WorkflowView view = service().view();

        assertThat(allSettings(view)).extracting(Setting::key).noneMatch(key -> key.startsWith("security"));
        assertThat(allSettings(view)).extracting(Setting::value).noneMatch(value -> value.contains("oidc-literal"));
    }

    @Test
    void aLiteralSecretInAnOverrideIsMaskedAndTheFileIsNamed() throws IOException {
        ConfigFixtures.materialize(configDir);
        replace(ConfigLoader.PIPELINE_FILE, "api_key: ${LLM_API_KEY}", "api_key: hunter2-literal");

        WorkflowView view = service().view();

        Setting apiKey = setting(view, "llm.api_key");
        assertThat(apiKey.value()).isEqualTo(Secrets.MASK);
        assertThat(apiKey.file()).isEqualTo("pipeline.yaml");
        assertThat(allSettings(view)).extracting(Setting::value).noneMatch(value -> value.contains("hunter2"));
    }

    @Test
    void aKnockoutNamesTheKeyItReadsEvenWhenTheOverrideLeavesItOut() throws IOException {
        // HardFilter still reads accept_unknown when the file omits it (as false), so the screen
        // must still name the key that decides the rejection.
        ConfigFixtures.materialize(configDir);
        replace(ConfigLoader.RULES_FILE, "    accept_unknown: true\n    reject_keywords_de", "    reject_keywords_de");

        WorkflowView view = service().view();

        assertThat(stage(view, "FILTER").knockouts())
                .filteredOn(knockout -> knockout.id().equals("remote-share"))
                .singleElement()
                .satisfies(knockout -> assertThat(knockout.keys())
                        .contains("hard_filters.remote.min_remote_percent", "hard_filters.remote.accept_unknown"));
    }

    // --- ISC-386: every stage a width bounds names it, at every width including one, and the key.

    @Test
    void theModelBoundStagesNameTheWidthTheyWorkAtAndTheKeyThatSetsIt() throws IOException {
        ConfigFixtures.materialize(configDir);
        replace(ConfigLoader.PIPELINE_FILE, "concurrency: ${LLM_CONCURRENCY:1}", "concurrency: 4");

        WorkflowView view = service().view();

        for (String id : List.of("DEDUPE", "CONTENT", "FIELDS", "SCORE", "RETRIEVAL")) {
            assertThat(stage(view, id).width()).as(id).isEqualTo(new StageWidth("llm.concurrency", 4));
        }
    }

    @Test
    void anAbsentWidthIsNamedAsOneAndNotLeftOut() throws IOException {
        ConfigFixtures.materialize(configDir);
        replace(ConfigLoader.PIPELINE_FILE, "  concurrency: ${LLM_CONCURRENCY:1}\n", "");

        WorkflowView view = service().view();

        assertThat(stage(view, "CONTENT").width()).isEqualTo(new StageWidth("llm.concurrency", 1));
    }

    @Test
    void enrichNamesTheFetchWidthRatherThanTheModelOne() throws IOException {
        ConfigFixtures.materialize(configDir);
        replace(ConfigLoader.PIPELINE_FILE, "concurrency: ${FETCH_CONCURRENCY:1}", "concurrency: 3");

        WorkflowView view = service().view();

        assertThat(stage(view, "ENRICH").width()).isEqualTo(new StageWidth("enrichment.fetch.concurrency", 3));
    }

    @Test
    void aStageNoWidthBoundsAndEveryIngestEntryNameNone() throws IOException {
        WorkflowView view = serviceWithTwoSources().view();

        for (String id : List.of("FILTER", "ARCHIVE", "OPEN", "PACKAGE", "DIGEST")) {
            assertThat(stage(view, id).width()).as(id).isNull();
        }
        assertThat(view.phases().getFirst().stages())
                .filteredOn(stage -> stage.kind().equals(WorkflowView.KIND_INGEST))
                .isNotEmpty()
                .allSatisfy(stage -> assertThat(stage.width()).isNull());
    }

    // --- helpers ---------------------------------------------------------------------------

    /** The shipped defaults with the sample newsletter switched on beside the manual inbox. */
    private WorkflowService serviceWithTwoSources() throws IOException {
        ConfigFixtures.materialize(configDir);
        replace(
                ConfigLoader.SOURCES_FILE,
                "id: sample-newsletter\n    enabled: false",
                "id: sample-newsletter\n    enabled: true");
        return service();
    }

    private WorkflowService service() {
        ConfigRegistry registry = mock(ConfigRegistry.class);
        var snapshot = ConfigFixtures.loaderFor(configDir, VALIDATOR, IMAP).load();
        when(registry.snapshot()).thenReturn(snapshot);
        return new WorkflowService(registry, new ConfigProperties(configDir.toString()));
    }

    private void replace(String file, String from, String to) throws IOException {
        Path path = configDir.resolve(file);
        String text = Files.readString(path, StandardCharsets.UTF_8);
        assertThat(text).as("fixture anchor in %s", file).contains(from);
        Files.writeString(path, text.replace(from, to), StandardCharsets.UTF_8);
    }

    private static Stream<Setting> allSettings(WorkflowView view) {
        return Stream.concat(
                view.phases().stream()
                        .flatMap(phase -> phase.stages().stream())
                        .flatMap(stage -> stage.settings().stream()),
                view.unread().stream());
    }

    private static Stage stage(WorkflowView view, String id) {
        return view.phases().stream()
                .flatMap(phase -> phase.stages().stream())
                .filter(stage -> stage.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static Setting setting(WorkflowView view, String key) {
        return allSettings(view)
                .filter(setting -> setting.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no setting " + key));
    }
}
