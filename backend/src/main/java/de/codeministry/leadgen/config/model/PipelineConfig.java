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
            boolean batch,
            @NotNull Models models,
            @Valid Budget budget) {

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
         *     separated. A list rather than a single value because comparing two judges is
         *     the only way to find out what a model is worth here, and a comparison that
         *     needs an edit to `.env` and a restart between the halves does not get made.
         *     <p><b>Comma separated rather than a YAML sequence, and that is the
         *     placeholder's doing.</b> Every value in a shipped file is a
         *     <code>${PLACEHOLDER}</code> resolved from `.env`, which substitutes text into
         *     a scalar; a sequence would have to be written out in the file itself, which
         *     is the one thing no committed file here does.
         */
        public record Models(
                String extraction, String scoring, String writing, String embedding, String scoringOptions) {

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

        public record Budget(@Min(0) int maxCallsPerDay, boolean cacheByMessageId) {}
    }

    public record Profile(@NotBlank String path) {}

    public record Rules(@NotBlank String path, boolean hotReload) {}

    /** Optional: without it `sources.yaml` in the configuration directory applies. */
    public record Sources(String path) {}

    public record Enrichment(boolean enabled, @NotBlank String after, @Valid @NotNull Fetch fetch, @Valid Extract extract) {

        public record Fetch(
                @NotNull Duration timeout,
                @Min(1) int rateLimitPerMinute,
                @NotBlank String userAgent,
                @NotNull Duration cacheTtl,
                boolean respectRobotsTxt) {}

        /**
         * @param strategy how the fields are read. Only {@code patterns} exists: a
         *     selector or a regular expression per field, in YAML. A `readability` value
         *     used to sit here and nothing implemented it.
         * @param fields the field name to the rule that finds it. The names are the
         *     contract with the enrichment stage, exactly as the eight names in
         *     `sources.yaml` are the contract with `OfferMapper`: a field spelled
         *     differently is extracted and then ignored, in silence.
         */
        public record Extract(@NotBlank String strategy, Map<String, @Valid Field> fields) {

            /**
             * @param css narrows the search to part of the page before the regex runs, or
             *     takes the element's text when there is no regex.
             * @param regex the value, or its first capturing group when {@code group} is set.
             * @param group which capturing group holds the value. 1 by default, because a
             *     pattern that matches "85 €/h" wants the 85 and not the whole phrase.
             */
            public record Field(String css, String regex, Integer group, String attr) {

                public int groupOrFirst() {
                    return group == null ? 1 : group;
                }
            }
        }
    }

    public record Packaging(@NotBlank String outputDir, @NotBlank String naming, List<@Valid Document> documents) {

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
