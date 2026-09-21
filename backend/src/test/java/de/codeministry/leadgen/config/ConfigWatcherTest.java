/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The watcher is driven by calling its poll directly. Waiting on the scheduler would
 * only measure the scheduler.
 */
class ConfigWatcherTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @TempDir
    Path configDir;

    private ConfigLoader loader;
    private ConfigRegistry registry;
    private ConfigWatcher watcher;

    @BeforeEach
    void setUp() {
        ConfigFixtures.materialize(configDir);
        loader = ConfigFixtures.loaderFor(configDir, VALIDATOR);
        registry = new ConfigRegistry(loader);
        watcher = new ConfigWatcher(registry, loader);
    }

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Test
    void appliesAValidChange() throws IOException {
        assertThat(registry.snapshot().rules().hardFilters().remote().minRemotePercent())
                .isEqualTo(80);

        rewrite("matching-rules.yaml", "min_remote_percent: 80", "min_remote_percent: 60");
        settle();

        assertThat(registry.snapshot().rules().hardFilters().remote().minRemotePercent())
                .isEqualTo(60);
    }

    @Test
    void refusesAChangeToTheAuthModeAndSaysToRestart() throws IOException {
        var before = registry.snapshot();

        // The issuer has to come with it, or the loader refuses the file first and this
        // would pass without the guard it is about.
        rewrite("pipeline.yaml", "auth: ${AUTH_MODE:none}", "auth: oidc");
        rewrite("pipeline.yaml", "issuer: ${OIDC_ISSUER:}", "issuer: https://auth.example/realms/x");
        settle();

        // The filter chain is built once, at startup. Applying this would change what the
        // configuration says about authentication without changing who may call the API,
        // which is a reload that appears to work and does not. Nothing else in the same
        // save is applied either: half a configuration is worse than none of it.
        assertThat(registry.snapshot()).isSameAs(before);
        assertThat(registry.snapshot().application().security().auth()).isEqualTo("none");
    }

    @Test
    void keepsTheLastGoodConfigurationWhenTheChangeIsInvalid() throws IOException {
        var before = registry.snapshot();

        rewrite("matching-rules.yaml", "apply_after: enrichment", "apply_after: hard_filter");
        settle();

        // A rejected reload must not take the running tool down, and must not leave it
        // running on half a configuration either.
        assertThat(registry.snapshot()).isSameAs(before);
    }

    @Test
    void waitsOneCycleBeforeActingOnAChange() throws IOException {
        rewrite("matching-rules.yaml", "min_remote_percent: 80", "min_remote_percent: 60");

        watcher.pollForChanges(); // first sighting only

        assertThat(registry.snapshot().rules().hardFilters().remote().minRemotePercent())
                .isEqualTo(80);
    }

    @Test
    void ignoresAChangeWhenHotReloadIsOff() throws IOException {
        rewrite("pipeline.yaml", "hot_reload: true", "hot_reload: false");
        settle();
        var withHotReloadOff = registry.snapshot();
        assertThat(withHotReloadOff.application().rules().hotReload()).isFalse();

        rewrite("matching-rules.yaml", "min_remote_percent: 80", "min_remote_percent: 60");
        settle();

        assertThat(registry.snapshot()).isSameAs(withHotReloadOff);
    }

    @Test
    void doesNothingWhileTheFilesAreUntouched() {
        var before = registry.snapshot();
        settle();
        assertThat(registry.snapshot()).isSameAs(before);
    }

    /**
     * The poll runs twice a second for the life of the process. It used to bind and validate
     * `pipeline.yaml` on every one of them, purely to learn which two file names to stamp —
     * thirty lines a minute in the deployed log announcing a read of a file nothing had
     * touched, and a full parse behind each line. The watch list is derived from the running
     * snapshot now, so an untouched configuration is read exactly once: at startup.
     */
    @Test
    void readsNothingFromDiskWhileTheFilesAreUntouched() {
        ListAppender<ILoggingEvent> log = new ListAppender<>();
        Logger loaderLog = (Logger) LoggerFactory.getLogger(ConfigLoader.class);
        log.start();
        loaderLog.addAppender(log);
        try {
            settle();
            settle();
        } finally {
            loaderLog.detachAppender(log);
        }

        assertThat(log.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("read from"));
    }

    /**
     * Two polls: the first sees the change, the second confirms it has settled.
     */
    private void settle() {
        watcher.pollForChanges();
        watcher.pollForChanges();
    }

    private void rewrite(String file, String from, String to) throws IOException {
        Path path = configDir.resolve(file);
        String content = Files.readString(path);
        assertThat(content).as("fixture must contain %s", from).contains(from);
        Files.writeString(path, content.replace(from, to));
    }
}
