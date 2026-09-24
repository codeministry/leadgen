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

import com.fasterxml.jackson.databind.json.JsonMapper;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigProperties;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.ConfigSource;
import de.codeministry.leadgen.workflow.WorkflowView.Setting;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/**
 * ISC-285: every leaf of the three shipped rule-bearing files is on the screen exactly once, and
 * nothing of the {@code security} block or of a credential is.
 *
 * <p>The expected leaves come from a walk of its own over the shipped text, deliberately not from
 * the service's flattener: comparing the service with itself would pass whatever it did. The rule
 * is the documented one — dotted keys, {@code []} after a non-empty sequence whose items are all
 * mappings, any other sequence, a scalar and an empty mapping as one leaf each.
 *
 * <p>Beyond coverage, every shipped leaf has to be a decision: owned by a catalog stage or named on
 * the catalog's explicit read-by-nothing list, and never both. At runtime an unknown key still
 * lands in the unread group; for a key the defaults ship, this test is what forces someone to say
 * where it belongs.
 */
class WorkflowCoverageTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    /** The three files the screen shows, by the names the catalog knows them under. */
    private static final List<String> FILES = List.of(
            WorkflowCatalog.FILE_MATCHING_RULES, WorkflowCatalog.FILE_PIPELINE, WorkflowCatalog.FILE_SKILL_PROFILE);

    /** The literal every credential in the fixture layer contains. */
    private static final String LITERAL = "hunter2-literal";

    @TempDir
    Path configDir;

    @Test
    void everyShippedLeafAppearsExactlyOnceAndNothingElseDoes() {
        Set<String> expected = shippedLeaves();
        List<String> actual = allSettings(service().view())
                .map(setting -> setting.file() + ":" + setting.key())
                .toList();

        assertThat(actual).as("a key filed twice").doesNotHaveDuplicates();
        assertThat(new LinkedHashSet<>(actual))
                .as("the response against the walk of the shipped files")
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(actual).noneMatch(key -> key.startsWith(WorkflowCatalog.FILE_PIPELINE + ":security"));
    }

    @Test
    void everyShippedLeafIsOwnedByAStageOrDeclaredReadByNothingAndNeverBoth() {
        List<String> undecided = new ArrayList<>();
        List<String> both = new ArrayList<>();
        for (String entry : shippedLeaves()) {
            String file = entry.substring(0, entry.indexOf(':'));
            String leaf = entry.substring(entry.indexOf(':') + 1);
            boolean owned = WorkflowCatalog.ownerOf(file, leaf).isPresent();
            boolean unread = WorkflowCatalog.isReadByNothing(file, leaf);
            if (!owned && !unread) {
                undecided.add(entry);
            }
            if (owned && unread) {
                both.add(entry);
            }
        }

        assertThat(undecided)
                .as("shipped leaves no stage owns and the read-by-nothing list does not name")
                .isEmpty();
        assertThat(both)
                .as("shipped leaves a stage owns and the read-by-nothing list names")
                .isEmpty();
    }

    @Test
    void everyReadByNothingEntryNamesAShippedLeaf() {
        Set<String> shipped = shippedLeaves();

        assertThat(WorkflowCatalog.readByNothing())
                .as("read-by-nothing entries that cover no shipped leaf")
                .allSatisfy(key -> assertThat(shipped)
                        .as("%s:%s", key.file(), key.path())
                        .anyMatch(entry -> key.covers(
                                entry.substring(0, entry.indexOf(':')), entry.substring(entry.indexOf(':') + 1))));
    }

    @Test
    void overTheShippedDefaultsTheUnreadGroupIsExactlyTheDeclaredList() {
        Set<String> declared = new LinkedHashSet<>();
        for (String entry : shippedLeaves()) {
            String file = entry.substring(0, entry.indexOf(':'));
            if (WorkflowCatalog.isReadByNothing(file, entry.substring(entry.indexOf(':') + 1))) {
                declared.add(entry);
            }
        }

        List<String> unread = service().view().unread().stream()
                .map(setting -> setting.file() + ":" + setting.key())
                .toList();

        assertThat(unread).containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    void aLayerWithLiteralCredentialsAndASecurityBlockLeaksNeither() throws Exception {
        WorkflowService service = service();
        // The service reads the file's own text, not the bound snapshot, so the layer on disk is
        // what reaches the screen. Laid over the shipped file after the snapshot is built, the
        // fixture need not satisfy the loader.
        try (InputStream fixture = getClass().getResourceAsStream("/workflow/pipeline.yaml")) {
            Objects.requireNonNull(fixture, "fixture workflow/pipeline.yaml");
            Files.copy(fixture, configDir.resolve(WorkflowCatalog.FILE_PIPELINE), StandardCopyOption.REPLACE_EXISTING);
        }
        assertThat(Files.readString(configDir.resolve(WorkflowCatalog.FILE_PIPELINE)))
                .as("the fixture carries what the test claims it does")
                .contains("security:", "api_key: " + LITERAL, "client_secret:");

        WorkflowView view = service.view();
        String json = JsonMapper.builder().build().writeValueAsString(view);

        assertThat(allSettings(view)).extracting(Setting::key).contains("llm.api_key", "llm.base_url");
        assertThat(json).doesNotContain("security").doesNotContain(LITERAL).doesNotContain("hunter2");
        assertThat(json).doesNotContain("leadgen-workflow-client").doesNotContain("id.example.org");
    }

    // --- helpers ---------------------------------------------------------------------------

    /** The shipped defaults, with the snapshot the real loader builds from them. */
    private WorkflowService service() {
        ConfigFixtures.materialize(configDir);
        ConfigRegistry registry = mock(ConfigRegistry.class);
        var snapshot = ConfigFixtures.loaderFor(configDir, VALIDATOR).load();
        when(registry.snapshot()).thenReturn(snapshot);
        return new WorkflowService(registry, new ConfigProperties(configDir.toString()));
    }

    /** Every leaf of the three shipped files as {@code file:path}, the security block left out. */
    private static Set<String> shippedLeaves() {
        Set<String> out = new LinkedHashSet<>();
        for (String file : FILES) {
            String text = ConfigSource.fromClasspath(file)
                    .map(ConfigSource::content)
                    .orElseThrow(() -> new IllegalStateException("no default ships for " + file));
            Object root = new Yaml().load(text);
            assertThat(root).as("%s is a mapping", file).isInstanceOf(Map.class);
            ((Map<?, ?>) root).forEach((key, value) -> {
                if (file.equals(WorkflowCatalog.FILE_PIPELINE) && "security".equals(String.valueOf(key))) {
                    return;
                }
                walk(file, String.valueOf(key), value, out);
            });
        }
        return out;
    }

    private static void walk(String file, String path, Object value, Set<String> out) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            map.forEach((key, child) -> walk(file, path + "." + key, child, out));
        } else if (value instanceof List<?> list
                && !list.isEmpty()
                && list.stream().allMatch(Map.class::isInstance)) {
            list.forEach(item -> walk(file, path + "[]", item, out));
        } else {
            out.add(file + ":" + path);
        }
    }

    private static Stream<Setting> allSettings(WorkflowView view) {
        return Stream.concat(
                view.phases().stream()
                        .flatMap(phase -> phase.stages().stream())
                        .flatMap(stage -> stage.settings().stream()),
                view.unread().stream());
    }
}
