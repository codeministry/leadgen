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
public record ApplicationConfig(
        @Min(1) int version,
        @Valid @NotNull Llm llm,
        @Valid @NotNull Profile profile,
        @Valid @NotNull Rules rules,
        @Valid @NotNull Enrichment enrichment,
        @Valid @NotNull Packaging packaging,
        @Valid Digest digest,
        @Valid Security security) {

    public record Llm(
            @NotBlank String provider,
            String baseUrl,
            String apiKey,
            @NotNull Models models,
            @Valid Budget budget) {

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
                String format) {}
    }

    public record Digest(
            boolean enabled,
            String schedule,
            String channel,
            @Valid Transport transport,
            List<String> recipients,
            List<String> include) {

        public record Transport(String type, String url) {}
    }

    public record Security(@NotBlank String auth, Map<String, String> oidc) {}
}
