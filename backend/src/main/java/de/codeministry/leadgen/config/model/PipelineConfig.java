package de.codeministry.leadgen.config.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import java.util.Map;

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
    public record Llm(String provider, String baseUrl, String apiKey, @NotNull Models models, @Valid Budget budget) {

        /** Whether scoring and writing can run at all. */
        public boolean configured() {
            return provider != null && !provider.isBlank() && apiKey != null && !apiKey.isBlank();
        }

        /**
         * Every model is optional. Without a language model the tool still runs —
         * hard filter, dedupe and enrichment are deterministic — it only loses
         * scoring and the cover letter.
         */
        public record Models(String extraction, String scoring, String writing, String embedding) {}

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

        public record Extract(@NotBlank String strategy, List<String> fields) {}
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
    public record Digest(
            boolean enabled, String schedule, String format, String outputDir, List<String> include) {}

    public record Security(@NotBlank String auth, Map<String, String> oidc) {}
}
