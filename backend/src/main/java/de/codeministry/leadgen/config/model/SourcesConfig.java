package de.codeministry.leadgen.config.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * `sources.yaml`. A new source is a block in here, never a code change — which is
 * why the extraction rules are data down to the CSS selector.
 */
public record SourcesConfig(
        @Min(1) int version,
        @NotNull List<@Valid Connection> connections,
        @NotNull List<@Valid Source> sources) {

    /** Credentials arrive exclusively as `${ENV}` placeholders, never as literals. */
    public record Connection(
            @NotBlank String id,
            @NotBlank String type,
            String host,
            Integer port,
            boolean ssl,
            String username,
            String password,
            String mode,
            Duration pollInterval) {}

    public record Source(
            @NotBlank String id,
            boolean enabled,
            @NotBlank String type,
            String connection,
            String url,
            String path,
            String glob,
            Duration schedule,
            @Valid Selector selector,
            @Valid @NotNull Extraction extraction,
            Map<String, String> defaults) {}

    public record Selector(
            String folder,
            List<String> from,
            List<String> excludeFrom,
            String subjectMatches,
            Integer sinceDays,
            boolean markSeen,
            String state) {}

    /**
     * {@code fallback} names what happens to the fields the deterministic rules did
     * not fill. For the measured newsletter it is {@code none}: CSS covers every
     * field, so no language model is involved in extraction at all.
     */
    public record Extraction(
            @NotBlank String strategy, String blockSelector, Map<String, Field> fields, String fallback) {

        public record Field(String css, String attr, String regex, String path, String html) {}
    }
}
