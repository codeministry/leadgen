/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.workflow;

import static de.codeministry.leadgen.workflow.WorkflowView.COST_FILE;
import static de.codeministry.leadgen.workflow.WorkflowView.COST_FREE;
import static de.codeministry.leadgen.workflow.WorkflowView.COST_MODEL;
import static de.codeministry.leadgen.workflow.WorkflowView.COST_NETWORK;

import de.codeministry.leadgen.filter.FilterStage;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the workflow screen knows about the pipeline before it opens a file: the five phases, the
 * eleven stages in the order {@code IngestService} times them, and the key paths each stage reads.
 * No value lives here, only paths; the values come from the winning file at request time.
 *
 * <p>Static and immutable rather than a bean: it is a table, not a collaborator, and nothing about
 * it changes with the configuration. The content is taken line by line from
 * {@code docs/BACKEND-FLOWS.md} §1 (the phase subgraphs and the node classes of its mermaid) and
 * §1e, and from {@code docs/WRITING-RULES.md} § "Every key, and what reads it". When those
 * documents and this class disagree, one of them is wrong, and the review compares them.
 *
 * <p><b>Each key is filed once.</b> Several stages read {@code scoring.thresholds.auto_shortlist}
 * or {@code llm.models.embedding}, and the screen shows each key exactly once, so a key read by
 * several stages is filed under the first of them in run order — where it first takes effect. A
 * fallback read does not count. One exception: the model connection ({@code llm.provider},
 * {@code base_url}, {@code api_key}, {@code timeout}, {@code batch}, {@code budget}, and the
 * scoring model) is filed under SCORE, because the run checks the judge before any work and
 * every other model stage borrows the same connection.
 *
 * <p><b>The Read phase is built at runtime.</b> It holds one entry per enabled source, which only
 * the loaded {@code sources.yaml} knows. {@link #INGEST} is the template for those entries; its
 * {@link StageEntry#keys() keys} are shared by every source and are therefore attached to the
 * first ingest entry only, so they too appear once.
 */
public final class WorkflowCatalog {

    /** The file that holds the run: models, enrichment, content, packaging, the digest. */
    public static final String FILE_PIPELINE = "pipeline.yaml";

    /** The file that holds the rules: knockouts, weights, thresholds, deduplication. */
    public static final String FILE_MATCHING_RULES = "matching-rules.yaml";

    /** The file the rules are measured against. */
    public static final String FILE_SKILL_PROFILE = "skill-profile.yaml";

    /**
     * One key path in one file.
     *
     * <p>{@code path} is dotted, with {@code []} after a segment that is a sequence of mappings
     * ({@code content.rules[].matches}); a sequence of scalars is one leaf. A path is also a
     * <b>prefix</b>: it covers a leaf that equals it, or that continues it with {@code .} or with
     * {@code []}. {@code enrichment.fetch} therefore covers {@code enrichment.fetch.timeout}, and
     * {@code content.rules} covers {@code content.rules[].kind}, while neither covers a sibling
     * that only shares its spelling. A prefix is used where the subtree is an open map or a list
     * the stage reads whole; everywhere else the leaf is named, so a key nothing reads stays
     * unclaimed and lands in the unread group.
     */
    public record KeyRef(String file, String path) {

        /** Whether the leaf {@code leaf} of {@code leafFile} is this key or lies beneath it. */
        public boolean covers(String leafFile, String leaf) {
            if (!file.equals(leafFile) || !leaf.startsWith(path)) {
                return false;
            }
            String rest = leaf.substring(path.length());
            return rest.isEmpty() || rest.startsWith(".") || rest.startsWith("[]");
        }
    }

    /**
     * One stage as the catalog knows it.
     *
     * @param id          the name {@code IngestService} times it under; {@code INGEST} for the
     *                    template the per-source entries are built from
     * @param phase       the id of the {@link PhaseEntry} it belongs to
     * @param description one sentence on what the stage does to an offer
     * @param costClasses the {@code WorkflowView.COST_*} values of its mermaid node class
     * @param promptId    the {@code PromptView} id of the prompt it sends, or null
     * @param keys        the key paths it reads, in the order the screen lists them
     */
    public record StageEntry(
            String id, String phase, String description, List<String> costClasses, String promptId, List<KeyRef> keys) {

        public StageEntry {
            costClasses = List.copyOf(costClasses);
            keys = List.copyOf(keys);
        }
    }

    /**
     * One phase: a subgraph id of the §1 mermaid, and the ids of the fixed stages inside it.
     * {@code read} lists none; its entries are the sources.
     */
    public record PhaseEntry(String id, List<String> stageIds) {

        public PhaseEntry {
            stageIds = List.copyOf(stageIds);
        }
    }

    /**
     * One knockout of the FILTER stage as the catalog knows it: the {@link FilterStage} it is, and
     * the key paths {@code filter.HardFilter} reads to decide it. Each path is also among the
     * FILTER stage's {@link StageEntry#keys() keys}; a knockout references settings, it does not
     * file them.
     */
    public record KnockoutEntry(FilterStage stage, List<KeyRef> keys) {

        public KnockoutEntry {
            keys = List.copyOf(keys);
        }

        /**
         * The wire id, {@link FilterStage#id()} — the form the funnel and
         * {@code AnalyticsQueryService} report a filter stage under, so the screen can join a
         * knockout to its removal count without a second mapping.
         */
        public String id() {
            return stage.id();
        }
    }

    /** The stage the knockouts belong to. */
    public static final String FILTER = "FILTER";

    /** The width every model-bound stage works at: one key, read by all five. */
    public static final String LLM_CONCURRENCY = "llm.concurrency";

    /** The width ENRICH fetches at, a key of its own because a fetch is not a model call. */
    public static final String FETCH_CONCURRENCY = "enrichment.fetch.concurrency";

    /** Stage id to the key that bounds how many adverts it works on at once. */
    private static final Map<String, String> WIDTH_KEYS = Map.of(
            "DEDUPE", LLM_CONCURRENCY,
            "ENRICH", FETCH_CONCURRENCY,
            "CONTENT", LLM_CONCURRENCY,
            "FIELDS", LLM_CONCURRENCY,
            "SCORE", LLM_CONCURRENCY,
            "RETRIEVAL", LLM_CONCURRENCY);

    /**
     * The key that bounds this stage's width, or empty for a stage that works one advert after
     * the other by construction — FILTER, ARCHIVE, OPEN, PACKAGE, DIGEST and every ingest entry.
     */
    public static Optional<String> widthKeyOf(String stageId) {
        return Optional.ofNullable(WIDTH_KEYS.get(stageId));
    }

    /** The template for every per-source entry of the Read phase. */
    public static final StageEntry INGEST = new StageEntry(
            "INGEST",
            "read",
            "Reads one source's documents, extracts the offers in them and stores each one.",
            List.of(COST_NETWORK),
            "extraction",
            List.of(pipeline("llm.models.extraction")));

    private static final List<PhaseEntry> PHASES = List.of(
            new PhaseEntry("read", List.of()),
            new PhaseEntry("sort", List.of("DEDUPE", "FILTER", "ARCHIVE")),
            new PhaseEntry("understand", List.of("ENRICH", "CONTENT", "FIELDS")),
            new PhaseEntry("judge", List.of("SCORE", "RETRIEVAL")),
            new PhaseEntry("hand", List.of("OPEN", "PACKAGE", "DIGEST")));

    private static final List<StageEntry> STAGES = List.of(
            new StageEntry(
                    "DEDUPE",
                    "sort",
                    "Merges an offer seen before, by fingerprint and then by vector, and flags a near match.",
                    List.of(COST_MODEL),
                    null,
                    List.of(
                            rules("deduplication.strategies"),
                            rules("deduplication.ttl_days"),
                            pipeline("llm.models.embedding"),
                            // The width of every model-bound stage. Filed here because the
                            // embedding batches are the first of them in run order; RETRIEVAL,
                            // CONTENT, FIELDS and the synchronous SCORE read it too.
                            pipeline("llm.concurrency"))),
            // The keys in FilterStage order: ABROAD, REMOTE_SHARE, OUT_OF_REACH, ROLE_OR_STACK,
            // NO_CORE_SKILL, CONTRACT_FORM.
            new StageEntry(
                    FILTER,
                    "sort",
                    "Judges every offer against the six knockouts, again on every run.",
                    List.of(COST_FREE),
                    null,
                    List.of(
                            rules("hard_filters.location.reject_keywords"),
                            rules("hard_filters.remote.min_remote_percent"),
                            rules("hard_filters.remote.accept_unknown"),
                            rules("hard_filters.location.onsite_cities"),
                            rules("hard_filters.remote.derive_from[].contains_any"),
                            rules("hard_filters.role.rejected_title_keywords"),
                            profile("core[].skill"),
                            profile("core[].aliases"),
                            rules("hard_filters.contract.rejected"))),
            new StageEntry(
                    "ARCHIVE",
                    "sort",
                    "Takes an offer off the working list once it is too old, and brings it back when the window reaches it again.",
                    List.of(COST_FREE),
                    null,
                    List.of(rules("hard_filters.freshness.max_age_days"))),
            new StageEntry(
                    "ENRICH",
                    "understand",
                    "Fetches the advert behind each surviving offer and reads the missing fields out of it.",
                    List.of(COST_NETWORK),
                    null,
                    // `enrichment.fetch` is a prefix: it files the fetch width,
                    // `enrichment.fetch.concurrency`, together with timeout and cache.
                    List.of(
                            pipeline("enrichment.enabled"),
                            pipeline("enrichment.fetch"),
                            pipeline("enrichment.extract"))),
            new StageEntry(
                    "CONTENT",
                    "understand",
                    "Separates the advert from the portal's furniture, by rule, then by cache, then by model.",
                    List.of(COST_MODEL),
                    "content",
                    List.of(pipeline("content.enabled"), pipeline("content.rules"), pipeline("llm.models.content"))),
            new StageEntry(
                    "FIELDS",
                    "understand",
                    "Reads start, duration and apply-by out of the advert.",
                    List.of(COST_MODEL),
                    "fields",
                    List.of(pipeline("fields.enabled"), pipeline("llm.models.fields"))),
            new StageEntry(
                    "SCORE",
                    "judge",
                    "Scores each offer from the rule factors and the judge, and puts it in a band.",
                    List.of(COST_MODEL),
                    "scoring",
                    List.of(
                            rules("version"),
                            rules("scoring.weights"),
                            rules("scoring.penalties"),
                            rules("scoring.saturation_core_count"),
                            rules("scoring.thresholds.auto_shortlist"),
                            rules("scoring.thresholds.review"),
                            rules("hard_filters.rate.min_hourly_eur"),
                            profile("core[].weight"),
                            profile("strong"),
                            profile("peripheral"),
                            profile("industries[].name"),
                            profile("industries[].match"),
                            profile("industries[].weight"),
                            profile("interest_topics"),
                            profile("disinterest_topics"),
                            profile("identity.roles"),
                            profile("identity.seniority"),
                            profile("identity.base"),
                            pipeline("llm.provider"),
                            pipeline("llm.base_url"),
                            pipeline("llm.api_key"),
                            pipeline("llm.timeout"),
                            pipeline("llm.batch"),
                            pipeline("llm.budget.max_calls_per_day"),
                            pipeline("llm.models.scoring"),
                            pipeline("llm.models.scoring_options"))),
            new StageEntry(
                    "RETRIEVAL",
                    "judge",
                    "Embeds the advert for the semantic search; a vector never decides a verdict.",
                    List.of(COST_MODEL),
                    null,
                    List.of(
                            pipeline("retrieval.enabled"),
                            pipeline("retrieval.neighbours"),
                            pipeline("retrieval.topic_floor"))),
            new StageEntry(
                    "OPEN",
                    "hand",
                    "Opens an application at NEW for every shortlisted offer that has none.",
                    List.of(COST_FREE),
                    null,
                    List.of()),
            new StageEntry(
                    "PACKAGE",
                    "hand",
                    "Retries the application folders a person asked for and the request could not build.",
                    List.of(COST_FILE),
                    // The letter is drafted here when `llm.models.writing` names a model, so this
                    // is where the Rules screen shows the writer's prompt (ISC-326). The cost
                    // class stays `file`: the folder is always written, the model only asked.
                    "writing",
                    List.of(
                            pipeline("packaging.output_dir"),
                            pipeline("packaging.naming"),
                            pipeline("packaging.documents[].id"),
                            pipeline("packaging.documents[].template"),
                            pipeline("llm.models.writing"),
                            profile("identity.name"),
                            profile("identity.brand"),
                            // Not in WRITING-RULES' table, but read: the id is the key
                            // ReferenceRanking and ProfileEmbeddings match a project's vector by.
                            profile("reference_projects[].id"),
                            profile("reference_projects[].title_de"),
                            profile("reference_projects[].title_en"),
                            profile("reference_projects[].pitch_de"),
                            profile("reference_projects[].pitch_en"),
                            profile("reference_projects[].from"),
                            profile("reference_projects[].to"),
                            profile("reference_projects[].role"),
                            profile("reference_projects[].stack"),
                            profile("cv_variants"),
                            profile("locale_primary"))),
            new StageEntry(
                    "DIGEST",
                    "hand",
                    "Renders the day's digest file, by band and with its reasons.",
                    List.of(COST_FILE),
                    null,
                    List.of(pipeline("digest"))));

    /**
     * The six knockouts in {@link FilterStage} order, built by walking the enum. The switch has no
     * default, so a new {@code FilterStage} value without an entry here does not compile.
     *
     * <p>Read off {@code HardFilter.judge}, not off the file: {@code OUT_OF_REACH} reads the
     * minimum share too, because at {@code 0} the city list stops applying. The rate floor is not
     * here — it applies after enrichment and is filed under SCORE — and neither is
     * {@code hard_filters.location.country_allowlist}, which nothing reads.
     */
    private static final List<KnockoutEntry> KNOCKOUTS = Arrays.stream(FilterStage.values())
            .map(stage -> new KnockoutEntry(stage, knockoutKeys(stage)))
            .toList();

    private WorkflowCatalog() {}

    /** The FILTER stage's knockouts, one per {@link FilterStage} value, in enum order. */
    public static List<KnockoutEntry> knockouts() {
        return KNOCKOUTS;
    }

    private static List<KeyRef> knockoutKeys(FilterStage stage) {
        return switch (stage) {
            case ABROAD -> List.of(rules("hard_filters.location.reject_keywords"));
            case REMOTE_SHARE ->
                List.of(rules("hard_filters.remote.min_remote_percent"), rules("hard_filters.remote.accept_unknown"));
            case OUT_OF_REACH ->
                List.of(
                        rules("hard_filters.remote.min_remote_percent"),
                        rules("hard_filters.location.onsite_cities"),
                        rules("hard_filters.remote.derive_from[].contains_any"));
            case ROLE_OR_STACK -> List.of(rules("hard_filters.role.rejected_title_keywords"));
            case NO_CORE_SKILL -> List.of(profile("core[].skill"), profile("core[].aliases"));
            case CONTRACT_FORM -> List.of(rules("hard_filters.contract.rejected"));
        };
    }

    /** The phases in run order. */
    public static List<PhaseEntry> phases() {
        return PHASES;
    }

    /** The phase ids in run order: {@code read, sort, understand, judge, hand}. */
    public static List<String> phaseIds() {
        return PHASES.stream().map(PhaseEntry::id).toList();
    }

    /** The eleven fixed stages in the order a run times them; {@link #INGEST} is not among them. */
    public static List<StageEntry> stages() {
        return STAGES;
    }

    /** The eleven fixed stage ids in run order, to compare with the names {@code IngestService} times. */
    public static List<String> stageIds() {
        return STAGES.stream().map(StageEntry::id).toList();
    }

    /** The fixed stages of one phase, in run order; empty for {@code read} and for an unknown id. */
    public static List<StageEntry> stagesOf(String phaseId) {
        return STAGES.stream().filter(stage -> stage.phase().equals(phaseId)).toList();
    }

    /** The fixed stage with this id, or {@link #INGEST} for {@code "INGEST"}. */
    public static Optional<StageEntry> stage(String id) {
        if (INGEST.id().equals(id)) {
            return Optional.of(INGEST);
        }
        return STAGES.stream().filter(stage -> stage.id().equals(id)).findFirst();
    }

    /**
     * The id of the stage that files this leaf, or empty when no stage reads it. {@code INGEST}
     * for a key of the ingest template.
     */
    public static Optional<String> ownerOf(String file, String leaf) {
        if (INGEST.keys().stream().anyMatch(key -> key.covers(file, leaf))) {
            return Optional.of(INGEST.id());
        }
        return STAGES.stream()
                .filter(stage -> stage.keys().stream().anyMatch(key -> key.covers(file, leaf)))
                .map(StageEntry::id)
                .findFirst();
    }

    /**
     * The keys the shipped files declare and no stage reads, taken from {@code docs/WRITING-RULES.md}
     * § "Every key, and what reads it" and checked against the code. "Nothing" means no stage of a
     * run: a key only the loader validates, the config watcher consults or the old rules screen
     * printed is listed here too, because none of them changes what happens to an offer.
     *
     * <p>The service does not need this list — an unowned leaf lands in the unread group either
     * way, and an override may add keys nobody has heard of. It exists so that a key the defaults
     * ship is a decision: {@code WorkflowCoverageTest} fails on a shipped leaf that is neither owned
     * by a stage nor named here, and on one that is both.
     */
    private static final List<KeyRef> READ_BY_NOTHING = List.of(
            // matching-rules.yaml
            rules("hard_filters.remote.reject_keywords_de"),
            // Only the keyword derivations are read (HardFilter.remoteTokensOf).
            rules("hard_filters.remote.derive_from[].field"),
            rules("hard_filters.remote.derive_from[].regex"),
            rules("hard_filters.remote.derive_from[].set"),
            rules("hard_filters.remote.derive_from[].confidence"),
            rules("hard_filters.location.country_allowlist"),
            rules("hard_filters.location.onsite_home_base"),
            rules("hard_filters.location.onsite_exceptions"),
            rules("hard_filters.rate.currency"),
            rules("hard_filters.rate.accept_unknown"),
            // Validated by the loader, which accepts only `enrichment`.
            rules("hard_filters.rate.apply_after"),
            rules("hard_filters.rate.reject_below_as"),
            rules("hard_filters.contract.allowed"),
            rules("hard_filters.language"),
            rules("scoring.thresholds.discard"),
            rules("deduplication.fingerprint_fields"),
            // Validated by the loader, which accepts only `keep_first_seen_as_primary`.
            rules("deduplication.merge_policy"),
            rules("follow_up"),
            // pipeline.yaml
            pipeline("version"),
            // The loader and this screen resolve the file names; no stage reads them.
            pipeline("profile.path"),
            pipeline("rules.path"),
            // Read by ConfigWatcher, which decides whether an edit is applied, not by a stage.
            pipeline("rules.hot_reload"),
            // Validated by the loader, which accepts only `hard_filter`.
            pipeline("enrichment.after"),
            // PackagingService reads only document.id() and document.template(); the CV entry's
            // source/by and the cover-letter/meta entries' generated/format are documentation only.
            pipeline("packaging.documents[].source"),
            pipeline("packaging.documents[].by"),
            pipeline("packaging.documents[].generated"),
            pipeline("packaging.documents[].format"),
            // skill-profile.yaml
            profile("version"),
            profile("core[].since"),
            profile("industries[].note"),
            profile("languages"),
            // No caller of SkillProfile.Identity.freelanceSince(); only .roles/.seniority/.base
            // (ChatClientJudge, at SCORE) and .name/.brand (PackagingService, at PACKAGE) are read.
            profile("identity.freelance_since"));

    /** The key paths of the shipped files that no stage reads. */
    public static List<KeyRef> readByNothing() {
        return READ_BY_NOTHING;
    }

    /** Whether this leaf is on the {@link #readByNothing()} list. */
    public static boolean isReadByNothing(String file, String leaf) {
        return READ_BY_NOTHING.stream().anyMatch(key -> key.covers(file, leaf));
    }

    private static KeyRef pipeline(String path) {
        return new KeyRef(FILE_PIPELINE, path);
    }

    private static KeyRef rules(String path) {
        return new KeyRef(FILE_MATCHING_RULES, path);
    }

    private static KeyRef profile(String path) {
        return new KeyRef(FILE_SKILL_PROFILE, path);
    }
}
