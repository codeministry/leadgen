/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.workflow;

import de.codeministry.leadgen.config.ConfigProperties;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.ConfigSnapshot;
import de.codeministry.leadgen.config.ConfigSource;
import de.codeministry.leadgen.config.Secrets;
import de.codeministry.leadgen.config.YamlMask;
import de.codeministry.leadgen.config.model.SourcesConfig;
import de.codeministry.leadgen.workflow.WorkflowCatalog.KnockoutEntry;
import de.codeministry.leadgen.workflow.WorkflowCatalog.PhaseEntry;
import de.codeministry.leadgen.workflow.WorkflowCatalog.StageEntry;
import de.codeministry.leadgen.workflow.WorkflowView.Knockout;
import de.codeministry.leadgen.workflow.WorkflowView.Phase;
import de.codeministry.leadgen.workflow.WorkflowView.Setting;
import de.codeministry.leadgen.workflow.WorkflowView.Stage;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * The pipeline as the rules screen shows it: {@link WorkflowCatalog}'s phases and stages, with
 * every key of the three rule-bearing files filed under the stage that reads it.
 *
 * <p>Built fresh on every call from {@link ConfigRegistry#snapshot()} and the files as they are
 * on disk now, so a hot reload is on the screen with the next request and nothing is cached that
 * could disagree with it.
 *
 * <h2>What must not reach the browser</h2>
 *
 * <p><b>The file's own text, never the bound snapshot.</b> The snapshot has every
 * {@code ${PLACEHOLDER}} resolved to what it stands for; the file says which variable to set,
 * which is the useful half. The same reasoning as {@code SourceDetailService}, and the same
 * endpoint class: one that stands behind nothing.
 *
 * <p><b>Parsed first, masked per value.</b> The mask is {@code ********}, and a plain scalar
 * beginning with {@code *} is a YAML alias, so a masked document stops composing — silently. The
 * text is therefore composed as it is, and each rendered value goes through {@link YamlMask} on
 * its own, as the one line {@code key: value}. That is {@link YamlMask}'s decision reused whole —
 * {@link Secrets}' key words, the view-only names, the placeholder rule, URL credentials and query
 * parameters — rather than a second secret rule beside it.
 *
 * <p><b>{@code security} is dropped before anything else.</b> Not masked, not filed as unread:
 * it decides who may call this very endpoint, and the screen has no business describing it.
 *
 * <h2>Leaf paths</h2>
 *
 * <p>Dotted keys. A sequence whose items are all mappings contributes {@code []}, and every item
 * is merged under the one path, so {@code content.rules[].kind} is a single setting whose value
 * lists each item's kind in file order. Any other sequence is one leaf, rendered compactly as
 * {@code [a, b]}. An empty mapping is a leaf too, rendered {@code {}}, so a key the file declares
 * never disappears for having nothing under it.
 *
 * <p>Each leaf is filed under the first stage whose {@link WorkflowCatalog.KeyRef} covers it, or
 * into {@link WorkflowView#unread()} when none does. Every leaf therefore appears exactly once.
 * For the shipped defaults the unread group is exactly {@link WorkflowCatalog#readByNothing()};
 * a key an override adds and no stage knows lands there too, shown rather than dropped.
 *
 * <h2>The Read phase</h2>
 *
 * <p>One entry per <i>enabled</i> source, in file order. The keys of the
 * {@link WorkflowCatalog#INGEST} template are shared by all of them and attach to the first
 * entry only. <b>With no enabled source</b> the Read phase has no entries, and the template's
 * keys land in the unread group: nothing reads them on a run that reads nothing, and a key is
 * shown rather than dropped.
 */
@Service
@RequiredArgsConstructor
public class WorkflowService {

    /** The top-level key of {@code pipeline.yaml} that never leaves the server. */
    static final String SECURITY = "security";

    /** The id {@link WorkflowCatalog#ownerOf} answers for a key of the ingest template. */
    private static final String INGEST = WorkflowCatalog.INGEST.id();

    private final ConfigRegistry config;
    private final ConfigProperties properties;

    /** The workflow as the configuration stands at this moment. */
    public WorkflowView view() {
        ConfigSnapshot snapshot = config.snapshot();
        Path dir = properties.configDirectory();

        // Catalog file name -> the name the loader actually reads. The rules and the profile can be
        // renamed in pipeline.yaml; the catalog knows them by their role, the screen by the file.
        Map<String, String> files = new LinkedHashMap<>();
        files.put(
                WorkflowCatalog.FILE_MATCHING_RULES,
                fileName(snapshot.application().rules().path(), WorkflowCatalog.FILE_MATCHING_RULES));
        files.put(WorkflowCatalog.FILE_PIPELINE, WorkflowCatalog.FILE_PIPELINE);
        files.put(
                WorkflowCatalog.FILE_SKILL_PROFILE,
                fileName(snapshot.application().profile().path(), WorkflowCatalog.FILE_SKILL_PROFILE));

        List<SourcesConfig.Source> enabled = snapshot.sources().sources().stream()
                .filter(SourcesConfig.Source::enabled)
                .toList();

        Map<String, List<Filed>> byStage = new LinkedHashMap<>();
        List<Setting> unread = new ArrayList<>();
        files.forEach((role, actual) -> {
            for (Leaf leaf : leaves(dir, role, actual)) {
                Setting setting = new Setting(leaf.path(), leaf.render(), actual);
                Optional<String> owner = WorkflowCatalog.ownerOf(role, leaf.path())
                        .filter(id -> !id.equals(INGEST) || !enabled.isEmpty());
                owner.ifPresentOrElse(
                        id -> byStage.computeIfAbsent(id, key -> new ArrayList<>())
                                .add(new Filed(role, setting)),
                        () -> unread.add(setting));
            }
        });

        List<Phase> phases = new ArrayList<>();
        for (PhaseEntry phase : WorkflowCatalog.phases()) {
            List<Stage> stages = new ArrayList<>();
            if (phase.id().equals(WorkflowCatalog.INGEST.phase())) {
                for (int i = 0; i < enabled.size(); i++) {
                    stages.add(Stage.ingest(
                            enabled.get(i).id(),
                            WorkflowCatalog.INGEST.description(),
                            WorkflowCatalog.INGEST.costClasses(),
                            asksAModel(enabled.get(i)) ? WorkflowCatalog.INGEST.promptId() : null,
                            i == 0 ? ordered(WorkflowCatalog.INGEST, byStage) : List.of()));
                }
            }
            for (StageEntry entry : WorkflowCatalog.stagesOf(phase.id())) {
                stages.add(Stage.stage(
                        entry.id(),
                        entry.description(),
                        entry.costClasses(),
                        entry.promptId(),
                        ordered(entry, byStage),
                        entry.id().equals(WorkflowCatalog.FILTER) ? knockouts() : null));
            }
            phases.add(new Phase(phase.id(), stages));
        }
        return new WorkflowView(phases, unread);
    }

    /**
     * The stage's settings in the order its catalog entry names the keys, and within one key in
     * file order — the catalog's order is the one a person reads the stage in.
     */
    private static List<Setting> ordered(StageEntry entry, Map<String, List<Filed>> byStage) {
        List<Filed> filed = byStage.getOrDefault(entry.id(), List.of());
        List<Setting> out = new ArrayList<>(filed.size());
        for (WorkflowCatalog.KeyRef key : entry.keys()) {
            filed.stream()
                    .filter(f -> key.covers(f.role(), f.setting().key()) && !out.contains(f.setting()))
                    .forEach(f -> out.add(f.setting()));
        }
        return out;
    }

    /**
     * The FILTER stage's knockouts in {@code FilterStage} order. Each knockout names the keys the
     * filter reads, from the catalog, and not the keys the winning file happens to declare: a file
     * that leaves one out still has the filter read it, at its default, so hiding the key would hide
     * what decides the rejection. A named key is among FILTER's settings when the file declares it.
     */
    private static List<Knockout> knockouts() {
        List<Knockout> out = new ArrayList<>();
        for (KnockoutEntry entry : WorkflowCatalog.knockouts()) {
            List<String> keys = entry.keys().stream()
                    .map(WorkflowCatalog.KeyRef::path)
                    .distinct()
                    .toList();
            out.add(new Knockout(entry.id(), entry.stage().description(), keys));
        }
        return out;
    }

    /**
     * The leaves of one file, in document order. Resolved through {@link ConfigSource#resolve},
     * the same two-layer lookup the loader makes, so the file shown is the file that won.
     */
    private static List<Leaf> leaves(Path dir, String role, String actual) {
        String text =
                ConfigSource.resolve(dir, actual).map(ConfigSource::content).orElse("");
        Node root = new Yaml().compose(new StringReader(text));
        Map<String, Leaf> leaves = new LinkedHashMap<>();
        if (root instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                String key = keyOf(tuple);
                if (role.equals(WorkflowCatalog.FILE_PIPELINE) && key.equals(SECURITY)) {
                    continue;
                }
                flatten(tuple.getValueNode(), key, leaves);
            }
        }
        return List.copyOf(leaves.values());
    }

    private static void flatten(Node node, String path, Map<String, Leaf> leaves) {
        switch (node) {
            case MappingNode mapping
            when !mapping.getValue().isEmpty() -> {
                for (NodeTuple tuple : mapping.getValue()) {
                    flatten(tuple.getValueNode(), path + "." + keyOf(tuple), leaves);
                }
            }
            case SequenceNode sequence
            when !sequence.getValue().isEmpty()
                    && sequence.getValue().stream().allMatch(MappingNode.class::isInstance) -> {
                for (Node item : sequence.getValue()) {
                    flatten(item, path + "[]", leaves);
                }
            }
            default -> leaves.computeIfAbsent(path, Leaf::new).values().add(compact(node, lastSegment(path)));
        }
    }

    /**
     * One node as a single line, masked by the key it sits under. A scalar is its value as the
     * file spells it, without the quotes; anything else is written in flow style.
     */
    private static String compact(Node node, String key) {
        return switch (node) {
            case ScalarNode scalar -> masked(key, scalar.getValue());
            case SequenceNode sequence ->
                "["
                        + String.join(
                                ", ",
                                sequence.getValue().stream()
                                        .map(item -> compact(item, key))
                                        .toList())
                        + "]";
            case MappingNode mapping ->
                "{"
                        + String.join(
                                ", ",
                                mapping.getValue().stream()
                                        .map(tuple -> keyOf(tuple) + ": " + compact(tuple.getValueNode(), keyOf(tuple)))
                                        .toList())
                        + "}";
            default -> Secrets.MASK;
        };
    }

    /**
     * The value as {@link YamlMask} would publish it on the line {@code key: value}.
     *
     * <p>Two cases are narrowed before that, both in the safe direction. A secret with a
     * {@code  #} in it would have the tail kept as a comment, and a secret over several lines
     * would have only its first masked; both become the mask whole. A key name outside the
     * characters {@code YamlMask} reads as a key is spelled with underscores for the probe,
     * which leaves {@link Secrets}' word split unchanged.
     */
    static String masked(String key, String value) {
        if (value.isEmpty()) {
            return value;
        }
        String probeKey = key.replaceAll("[^A-Za-z0-9_.\\-]", "_");
        boolean secret = !maskLine(probeKey, "x").equals("x");
        if (secret && (value.contains("\n") || value.contains(" #"))) {
            return Secrets.MASK;
        }
        return String.join(
                "\n", value.lines().map(line -> maskLine(probeKey, line)).toList());
    }

    private static String maskLine(String key, String value) {
        String prefix = key + ": ";
        String out = YamlMask.apply(prefix + value);
        if (out.endsWith("\n")) {
            out = out.substring(0, out.length() - 1);
        }
        // Anything but the line coming back under its own key means the masker read it
        // differently than it was built; the safe answer is to show nothing of it.
        return out.startsWith(prefix) ? out.substring(prefix.length()) : Secrets.MASK;
    }

    private static String keyOf(NodeTuple tuple) {
        return tuple.getKeyNode() instanceof ScalarNode scalar ? scalar.getValue() : compact(tuple.getKeyNode(), "");
    }

    /**
     * The key a value sits under: the last dotted segment, without a trailing {@code []}. A
     * {@code []} is always followed by a dot or the end, so the last dot is the cut.
     */
    private static String lastSegment(String path) {
        String bare = path.endsWith("[]") ? path.substring(0, path.length() - 2) : path;
        return bare.substring(bare.lastIndexOf('.') + 1);
    }

    /**
     * Whether reading this source sends the extraction prompt: only when a model extracts the whole
     * document or fills what the rules left. A source whose rules cover every field sends none, and
     * its entry therefore names no prompt. Inheritance is already resolved in the snapshot.
     */
    private static boolean asksAModel(SourcesConfig.Source source) {
        SourcesConfig.Extraction extraction = source.extraction();
        return "llm".equals(extraction.strategy()) || "llm".equals(extraction.fallback());
    }

    /**
     * A path in {@code pipeline.yaml} names a file, never a location. The loader's rule, repeated
     * because its helper is private: only the file name is used, and a blank path is the default.
     */
    private static String fileName(String configured, String fallback) {
        if (configured == null || configured.isBlank()) {
            return fallback;
        }
        Path name = Path.of(configured).getFileName();
        return name == null ? fallback : name.toString();
    }

    /** A setting with the catalog file it is compared under, which a rename does not change. */
    private record Filed(String role, Setting setting) {}

    /**
     * One leaf path and the values found under it: one for a plain key, one per item for a key
     * inside a sequence of mappings.
     */
    private record Leaf(String path, List<String> values) {

        Leaf(String path) {
            this(path, new ArrayList<>());
        }

        String render() {
            return path.contains("[]") ? "[" + String.join(", ", values) + "]" : values.getFirst();
        }
    }
}
