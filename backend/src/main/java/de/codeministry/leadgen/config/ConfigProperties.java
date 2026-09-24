/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Every {@code leadgen.*} Spring property the process reads, in one typed place. These are
 * the last things allowed to be wired into the process rather than into a YAML file,
 * because something has to say where the YAML files are, and when the process runs.
 *
 * <p>The defaults repeat the ones in {@code application.yaml}, so a context that does not
 * load that file binds the same values the shipped one does.
 *
 * @param configDir where the configuration files live; resolved by {@link #configDirectory()}
 * @param version what the header shows, and the only place the running build names itself;
 *     the deploy sets it from the image tag. Not {@code @NotBlank}: {@code .env.example}
 *     ships {@code LEADGEN_VERSION=} and Compose injects that as an empty string, which the
 *     header shows as empty rather than refusing to start
 * @param configPollInterval how often the configuration files are checked for changes; two
 *     polls are needed before a change is applied, so the worst case is twice this value.
 *     Typed and validated here; the {@code @Scheduled} string on {@code ConfigWatcher} reads
 *     the same key, because an annotation attribute cannot read a bean
 * @param scoreBatchPollInterval how often a submitted scoring batch is asked whether it has
 *     ended; only read when {@code llm.batch} is on. The same mirror arrangement, for the
 *     {@code @Scheduled} string on {@code ScoreBatchCollector}
 * @param ingestCron when the tool runs a pass of its own; {@code -} is Spring's disabled
 *     marker and the default, so the shipped artifact reads nobody's mailbox
 * @param security the bind-related switch {@code SecurityConfig} reads once at startup
 */
@Validated
@ConfigurationProperties(prefix = "leadgen")
public record ConfigProperties(
        @NotBlank String configDir,
        @DefaultValue(DEFAULT_VERSION) String version,
        @DefaultValue(DEFAULT_CONFIG_POLL_INTERVAL) Duration configPollInterval,
        @DefaultValue(DEFAULT_SCORE_BATCH_POLL_INTERVAL) Duration scoreBatchPollInterval,
        @NotBlank @DefaultValue(DEFAULT_INGEST_CRON) String ingestCron,
        @Valid @NotNull @DefaultValue Security security) {

    // One home per default, read by the bound constructor and the hand-built one alike, so
    // the two cannot drift. `@DefaultValue` takes a compile-time constant, hence strings.
    private static final String DEFAULT_VERSION = "0.5.0";
    private static final String DEFAULT_CONFIG_POLL_INTERVAL = "PT2S";
    private static final String DEFAULT_SCORE_BATCH_POLL_INTERVAL = "PT5M";
    private static final String DEFAULT_INGEST_CRON = "-";

    /** The one Spring binds. Named, because the record has a second constructor below. */
    @ConstructorBinding
    public ConfigProperties {}

    /**
     * Only a directory and every other key at its default, for code that builds this by hand
     * rather than having Spring bind it — the tests that point a loader at a fixture tree.
     */
    public ConfigProperties(String configDir) {
        this(
                configDir,
                DEFAULT_VERSION,
                Duration.parse(DEFAULT_CONFIG_POLL_INTERVAL),
                Duration.parse(DEFAULT_SCORE_BATCH_POLL_INTERVAL),
                DEFAULT_INGEST_CRON,
                new Security(false));
    }

    // `packages-dir` and `inbox-dir` used to sit here as well, read by nothing: the packages
    // directory is `packaging.output_dir` in pipeline.yaml and the inbox is a source's `path`
    // in sources.yaml, both of which the tool reads itself. Two Spring properties nobody
    // consumed, whose only effect was to make `PACKAGES_DIR` and `INBOX_DIR` look like they
    // meant something here too.

    /**
     * A relative path is searched for upwards from the working directory, because the
     * working directory is not one thing: Gradle's `bootRun` runs in `backend/`, an IDE
     * run configuration in the repository root, and a jar wherever it happens to sit. A
     * default that is correct in one of them is wrong in the others, and the symptom is a
     * missing file with a path nobody recognises.
     */
    public Path configDirectory() {
        return Directories.resolve(configDir);
    }

    /**
     * {@code leadgen.security.*}.
     *
     * @param allowOpenBind asserts that something outside this process decides who reaches
     *     the port, which is the only thing that makes {@code security.auth: none} survivable
     *     on a bind past loopback; Compose sets it and limits reach on the host side instead
     */
    public record Security(@DefaultValue("false") boolean allowOpenBind) {}
}
