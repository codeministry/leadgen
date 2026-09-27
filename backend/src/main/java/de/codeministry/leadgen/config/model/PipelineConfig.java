/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * `application.yaml` from the config directory.
 *
 * <p>Not to be confused with the Spring {@code application.yaml} on the classpath.
 * That one wires the process (datasource, ports, where the config directory is);
 * this one is the tool's own configuration and is edited by the user. The name
 * collision is the concept's, and renaming it is a bigger change than living with
 * it — but a stack trace mentioning "application.yaml" can mean either file.
 */
public record PipelineConfig(
        @Min(1) int version,
        @Valid @NotNull Llm llm,
        @Valid @NotNull Profile profile,
        @Valid @NotNull Rules rules,
        @Valid Sources sources,
        @Valid @NotNull Enrichment enrichment,
        @Valid Content content,
        @Valid Fields fields,
        @Valid Retrieval retrieval,
        @Valid @NotNull Packaging packaging,
        @Valid Digest digest,
        @Valid Security security) {

    /**
     * Every field is optional, and an empty block is the working state on a fresh clone.
     * Without a model the tool still runs: the hard filter that removes 83.5 % of the
     * offers is deterministic, and only scoring and the cover letter are lost.
     */
    public record Llm(
            String provider,
            String baseUrl,
            String apiKey,
            Duration timeout,
            boolean batch,
            @NotNull Models models,
            @Valid Budget budget,
            @Min(1) Integer concurrency) {

        /**
         * Every component but the width, which is then absent and reads as {@code 1}. For code
         * that builds this by hand rather than having the loader bind it — the tests that need a
         * model block and have nothing to say about concurrency.
         */
        public Llm(
                String provider,
                String baseUrl,
                String apiKey,
                Duration timeout,
                boolean batch,
                Models models,
                Budget budget) {
            this(provider, baseUrl, apiKey, timeout, batch, models, budget, null);
        }

        /**
         * How many adverts the model-bound stages work at once: CONTENT, FIELDS, the
         * synchronous SCORE, and the embedding batches of DEDUPE and RETRIEVAL.
         *
         * <p><b>An Integer, so absent is not zero.</b> Absent means {@code 1}, today's
         * sequential run, and a configuration written before the key keeps working; the
         * accessor is what guarantees no reader ever sees the null. {@code @Min(1)} refuses a
         * zero, which would otherwise mean a stage that never starts. {@code ConfigLoader}
         * refuses a width above the database connection pool.
         *
         * <p>Read from the snapshot a stage starts with, never cached: a reload swaps the
         * snapshot whole and the next stage picks the new width up without a restart.
         */
        @Override
        public Integer concurrency() {
            return concurrency == null ? 1 : concurrency;
        }

        /**
         * How long one request to a model may take before it is given up on.
         *
         * <p><b>Measured, not guessed.</b> The clients were built with a fixed 30 s, which is
         * comfortable for a hosted endpoint and too short for a local one: a cold
         * `gpt-oss:20b` needs longer to load than to answer, and the stage then reports
         * "without a usable answer" — a sentence about the model's reply for a reply that
         * never arrived. Measured on 2026-09-17 against the deployed instance: content,
         * fields and scoring each gave up, three stages of a run lost to a ceiling nothing
         * could raise without a rebuild.
         *
         * <p>Absent means {@link #DEFAULT_TIMEOUT}, so a configuration file that predates the
         * key keeps working — and the accessor below is what guarantees that no reader ever
         * sees the null.
         */
        public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

        @Override
        public Duration timeout() {
            return timeout == null ? DEFAULT_TIMEOUT : timeout;
        }

        /**
         * The one wire format whose batch endpoint is implemented, named here rather than
         * in either of the two places that need it. {@code ConfigLoader} refuses `batch:
         * true` for anything else at load, and the judge factory builds the batching judge
         * for this provider — two copies of the same string would disagree exactly once,
         * and the symptom would be a batch flag that is accepted and then ignored.
         */
        public static final String BATCHING_PROVIDER = "anthropic";

        /**
         * Every model is optional. Without a language model the tool still runs —
         * hard filter, dedupe and enrichment are deterministic — it only loses
         * scoring and the cover letter.
         */
        /**
         * @param scoringOptions the alternatives offered beside {@link #scoring}, comma
         *                       separated. A list rather than a single value because comparing two judges is
         *                       the only way to find out what a model is worth here, and a comparison that
         *                       needs an edit to `.env` and a restart between the halves does not get made.
         *                       <p><b>Comma separated rather than a YAML sequence, and that is the
         *                       placeholder's doing.</b> Every value in a shipped file is a
         *                       <code>${PLACEHOLDER}</code> resolved from `.env`, which substitutes text into
         *                       a scalar; a sequence would have to be written out in the file itself, which
         *                       is the one thing no committed file here does.
         * @param content        the content classifier's model. Empty means {@link #scoring}'s
         *                       configured default; {@code ModelChoice.content} is the one place that decides.
         * @param fields         the field extractor's model. Empty means {@link #scoring}'s
         *                       configured default; {@code ModelChoice.fields} is the one place that decides.
         */
        public record Models(
                String extraction,
                String scoring,
                String writing,
                String embedding,
                String scoringOptions,
                String content,
                String fields) {

            /**
             * Every model that may be asked to judge, the configured default first.
             *
             * <p><b>This is an allowlist, not a suggestion.</b> The chosen model arrives as
             * a request parameter, and a parameter passed through unchecked is an arbitrary
             * model name on a billed endpoint. {@code Judges} refuses anything not in here.
             *
             * <p>{@link #scoring} is always the first entry, so the list is never empty
             * while a judge is configured at all and the default never has to be repeated
             * in `scoring_options`.
             */
            public List<String> scoringChoices() {
                return Stream.concat(Stream.ofNullable(scoring), split(scoringOptions))
                        .map(String::trim)
                        .filter(model -> !model.isEmpty())
                        .distinct()
                        .toList();
            }

            private static Stream<String> split(String options) {
                return options == null ? Stream.empty() : Arrays.stream(options.split(","));
            }
        }

        /**
         * @param maxCallsPerDay how many requests a day may leave for a model, counting every
         *                       stage alike. <b>Null and zero are different answers</b>: absent means no ceiling,
         *                       and {@code 0} means none at all. A primitive here bound an absent key to zero and
         *                       would have stopped every call in a file that merely names the block.
         */
        public record Budget(@Min(0) Integer maxCallsPerDay) {}
    }

    /**
     * Which parts of a fetched advert are the advert.
     *
     * <p>Optional, and absent means the stage does not run: an advert is then shown exactly
     * as the portal wrapped it, which is what every version before this did.
     *
     * @param rules patterns that decide a block for free, before anything is asked of a
     *              model. An <b>optimisation and not the mechanism</b> — they exist so a
     *              fresh clone with no key still hides the obvious furniture, and when one
     *              stops matching the block falls through to the cache and then to the
     *              model rather than being silently mislabelled.
     */
    public record Content(boolean enabled, List<@Valid Rule> rules) {

        /**
         * @param kind    one of the content kinds, as a string. Deliberately not the enum:
         *                the configuration model is read by everything and depends on no
         *                stage, exactly as a source's `type` is a string here and an
         *                implementation elsewhere. A name nothing knows is logged and
         *                dropped where the rules are compiled.
         * @param matches a regular expression, matched against the block's normalised text.
         *                <b>Single quotes in YAML</b> — a double-quoted scalar only allows a
         *                fixed set of escapes, and the file then fails to parse with nothing
         *                pointing at the pattern.
         */
        public record Rule(@NotBlank String kind, @NotBlank String matches) {}
    }

    /**
     * When an engagement starts, how long it runs, and by when it has to be answered.
     *
     * <p>Optional, and absent means the stage does not run: {@code starts_on} and {@code
     * duration} then hold whatever the enrichment patterns captured, which is what every
     * version before this did.
     *
     * <p>There is nothing to configure beyond the switch, and that is deliberate. No
     * selectors, because the stage reads the advert the content stage already cleaned rather
     * than the page; no patterns, because a pattern table is exactly what this replaces; and
     * no model key, because it reads {@code llm.models.scoring} like the classifier does — a
     * {@code models.fields} key would be a third allowlist for a bounded question answered in
     * three lines of JSON.
     */
    public record Fields(boolean enabled) {}

    /**
     * The retrieval index: one vector per offer over the whole de-furnitured advert, which is
     * what a semantic search reads.
     *
     * <p><b>A switch of its own although {@code llm.models.embedding} is already one.</b>
     * Without it, configuring an embedding model for <i>deduplication</i> — where the bands are
     * measured and the behaviour is proven — would silently switch on an indexing pass that is
     * neither. Two separable decisions, and a second switch is the only way to separate them.
     *
     * <p>Off by default, unlike {@code content.enabled} and {@code fields.enabled}: this column
     * has no measured threshold behind it, so a fresh clone should do what it did before.
     *
     * <p>No model key. It reads {@code llm.models.embedding}, the same one deduplication reads,
     * for the reason a {@code models.content} and a {@code models.fields} key were each refused.
     *
     * @param enabled    whether the stage runs at all.
     * @param neighbours how many nearest offers a semantic search may select. <b>A count and
     *                   deliberately not a similarity threshold:</b> the search narrows the set
     *                   and does not rank it, so nothing here has to be measured before the
     *                   stage can be switched on. A threshold would.
     */
    /**
     * @param topicFloor the cosine similarity at which a topic's own name counts as matching an
     *                   advert's retrieval vector in the shortlist's topic filter. A similarity and
     *                   not a count, so it is measured before it acts; unset, the topic filter reads
     *                   the stored alias matches alone. It narrows the filter and never moves a score.
     */
    public record Retrieval(
            boolean enabled,
            @Min(1) Integer neighbours,

            @jakarta.validation.constraints.DecimalMin("0.0") @jakarta.validation.constraints.DecimalMax("1.0") Double topicFloor) {}

    public record Profile(@NotBlank String path) {}

    public record Rules(@NotBlank String path, boolean hotReload) {}

    /**
     * Optional: without it `sources.yaml` in the configuration directory applies.
     */
    public record Sources(String path) {}

    public record Enrichment(
            boolean enabled,
            @NotBlank String after,
            @Valid @NotNull Fetch fetch,
            @Valid Extract extract) {

        /**
         * @param maxPerRun how many ads one pass fetches, at most. A hard cap: the unit is
         *                  reserved before the pass waits for a permit, so a pass never sends more ads
         *                  than this, whether the window was full or had room. It also says how long a
         *                  run is prepared to wait: the limiter refuses rather than waits, so without
         *                  it a pass fetches one minute's worth and defers the rest — measured: 480
         *                  due, 20 fetched, and a backlog that needs one run per twenty offers to
         *                  clear. Unset means {@code rateLimitPerMinute}, so a configuration written
         *                  before this key behaves as it did. Counted per ad, not per request: the
         *                  retries of a 5xx take window permits of their own, not budget units.
         */
        public record Fetch(
                @NotNull Duration timeout,
                @Min(1) int rateLimitPerMinute,
                @Min(1) Integer maxPerRun,
                @NotBlank String userAgent,
                @NotNull Duration cacheTtl,
                boolean respectRobotsTxt,
                @Min(1) Integer concurrency) {

            /**
             * Every component but the width, which is then absent and reads as {@code 1}. For
             * code that builds this by hand rather than having the loader bind it.
             */
            public Fetch(
                    Duration timeout,
                    int rateLimitPerMinute,
                    Integer maxPerRun,
                    String userAgent,
                    Duration cacheTtl,
                    boolean respectRobotsTxt) {
                this(timeout, rateLimitPerMinute, maxPerRun, userAgent, cacheTtl, respectRobotsTxt, null);
            }

            /**
             * How many fetches ENRICH has in flight at once. Absent means {@code 1}, the
             * sequential pass every version before this ran.
             *
             * <p><b>Width, not volume.</b> The rate window and {@link #budget()} still bound
             * what leaves the machine; this only lets several ads wait on the network inside
             * that window instead of one after the other. {@code ConfigLoader} refuses a width
             * above the database connection pool.
             */
            @Override
            public Integer concurrency() {
                return concurrency == null ? 1 : concurrency;
            }

            /**
             * What one pass may fetch, with the fallback applied once and in one place.
             */
            public int budget() {
                return maxPerRun == null ? rateLimitPerMinute : maxPerRun;
            }
        }

        /**
         * @param strategy how the fields are read. Only {@code patterns} exists: a
         *                 selector or a regular expression per field, in YAML. A `readability` value
         *                 used to sit here and nothing implemented it.
         * @param fields   the field name to the rule that finds it. The names are the
         *                 contract with the enrichment stage, exactly as the eight names in
         *                 `sources.yaml` are the contract with `OfferMapper`: a field spelled
         *                 differently is extracted and then ignored, in silence.
         */
        public record Extract(@NotBlank String strategy, Map<String, @Valid Field> fields) {

            /**
             * @param css   narrows the search to part of the page before the regex runs, or
             *              takes the element's text when there is no regex.
             * @param regex the value, or its first capturing group when {@code group} is set.
             * @param group which capturing group holds the value. 1 by default, because a
             *              pattern that matches "85 €/h" wants the 85 and not the whole phrase.
             */
            public record Field(String css, String regex, Integer group, String attr) {

                public int groupOrFirst() {
                    return group == null ? 1 : group;
                }
            }
        }
    }

    public record Packaging(
            @NotBlank String outputDir, @NotBlank String naming, List<@Valid Document> documents) {

        /**
         * One entry per file in an application package. The fields are mutually
         * exclusive by kind — a CV names a {@code source}, a cover letter a
         * {@code template}, the metadata a {@code format} — so all of them are
         * optional here and the packaging step reads the one that applies.
         */
        public record Document(
                @NotBlank String id,
                String source,
                String by,
                String template,
                boolean generated,
                String format,
                String mode) {}
    }

    /**
     * The digest is rendered to a file and never sent. There is deliberately no transport,
     * no recipient and no channel: the application has no send path, and a configuration
     * that modelled one would be an invitation to add it.
     */
    public record Digest(boolean enabled, String format, String outputDir, List<String> include) {}

    public record Security(@NotBlank String auth, Map<String, String> oidc) {}
}
