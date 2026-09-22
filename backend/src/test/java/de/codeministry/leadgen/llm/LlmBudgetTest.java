/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.llm;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The day's allowance: who may send, and what the two edges of the number mean.
 *
 * <p>The limit is rewritten in the materialised configuration per test rather than mocked,
 * because what is under test includes the binding — an absent key and a zero are different
 * answers, and a record component that is a primitive cannot tell them apart.
 */
@SpringBootTest
@Testcontainers
class LlmBudgetTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    /**
     * Made once in a static initialiser and never inside the property supplier.
     *
     * <p>Spring may resolve a {@code @DynamicPropertySource} supplier more than once, so a
     * supplier that creates a directory hands out a different one each time: the loader keeps
     * the first, the test writes into the last, and every rewritten limit is read from a file
     * nobody loads. The symptom is a configuration that reloads cleanly and never changes.
     */
    static final Path CONFIG_DIRECTORY = freshConfigDirectory();

    @Autowired
    private LlmBudget budget;

    @Autowired
    private de.codeministry.leadgen.config.ConfigRegistry config;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG_DIRECTORY::toString);
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM llm_call_budget");
        // The shipped file again, because each test rewrites it and the edits would otherwise
        // accumulate: the one that deletes the key would leave the next one with nothing to
        // rewrite, and the failure names the shipped file rather than the test before it.
        ConfigFixtures.materialize(CONFIG_DIRECTORY);
        config.reload();
    }

    @Test
    void handsOutExactlyWhatTheNumberSays() {
        limit("3");

        assertThat(budget.take()).isTrue();
        assertThat(budget.take()).isTrue();
        assertThat(budget.take()).isTrue();
        assertThat(budget.take()).isFalse();

        // The refused call took nothing: the count is the number of requests that were
        // actually allowed to leave, which is what the next day's reader would compare.
        assertThat(budget.used()).isEqualTo(3);
    }

    @Test
    void zeroMeansNoneAndNotNoLimit() {
        // The other reading is the expensive one. Somebody writing 0 to mean "off" would get
        // a bill; this way round they get a run that stops and says so on the first day.
        limit("0");

        assertThat(budget.take()).isFalse();
        assertThat(budget.used()).isZero();
    }

    @Test
    void anAbsentBlockIsNoCeilingAtAll() {
        // A fresh clone has no `budget:` and must not be limited by a number nobody wrote.
        withoutBudgetBlock();

        for (int call = 0; call < 20; call++) {
            assertThat(budget.take()).isTrue();
        }
        // Nothing is counted either: there is nothing to count against.
        assertThat(budget.used()).isZero();
    }

    @Test
    void anAbsentNumberInsideThePresentBlockIsAlsoNoCeiling() {
        // The trap a primitive `int` would have set: an absent key binds to zero, and a file
        // that merely names the block would stop every call in the pipeline.
        onlyTheCacheKey();

        assertThat(budget.take()).isTrue();
        assertThat(budget.take()).isTrue();
    }

    @Test
    void twoStagesCannotBothTakeTheLastCall() throws Exception {
        // Between a SELECT and an UPDATE there is a window where both read the same last
        // remaining call. The check and the increment are one statement for this reason, and
        // this is the test that fails if somebody splits them.
        limit("1");

        int threads = 8;
        var barrier = new CyclicBarrier(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<Boolean>> calls = new java.util.ArrayList<>();
            for (int index = 0; index < threads; index++) {
                calls.add(() -> {
                    barrier.await();
                    return budget.take();
                });
            }
            long granted = pool.invokeAll(calls).stream()
                    .filter(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .count();
            assertThat(granted).isEqualTo(1);
        }
        assertThat(budget.used()).isEqualTo(1);
    }

    @Test
    void yesterdaysCallsAreNotTodays() {
        limit("2");
        jdbc.update("INSERT INTO llm_call_budget (day, calls) VALUES (current_date - 1, 999)");

        assertThat(budget.take()).isTrue();
        assertThat(budget.used()).isEqualTo(1);
    }

    /**
     * Rewrites `max_calls_per_day` in the materialised configuration and reloads, so what is
     * exercised is the same binding the application uses.
     */
    private void limit(String value) {
        rewrite(text -> set(text, "max_calls_per_day", value));
    }

    private void withoutBudgetBlock() {
        rewrite(text -> text.replaceAll("(?m)^  budget:\\n(^    .*\\n)+", ""));
    }

    private void onlyTheCacheKey() {
        rewrite(text -> text.replaceAll("(?m)^    max_calls_per_day:.*\\n", ""));
    }

    private void rewrite(java.util.function.UnaryOperator<String> edit) {
        Path pipeline = CONFIG_DIRECTORY.resolve("pipeline.yaml");
        try {
            Files.writeString(pipeline, edit.apply(Files.readString(pipeline, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        config.reload();
    }

    private static String set(String yaml, String key, String value) {
        Matcher matcher =
                Pattern.compile("(?m)^([ \\t]*)" + Pattern.quote(key) + ":.*$").matcher(yaml);
        if (!matcher.find()) {
            throw new IllegalStateException("no `" + key + ":` in the shipped pipeline.yaml");
        }
        return matcher.replaceFirst("$1" + key + ": " + Matcher.quoteReplacement(value));
    }

    private static Path freshConfigDirectory() {
        try {
            Path dir = Files.createTempDirectory("leadgen-budget");
            dir.toFile().deleteOnExit();
            return ConfigFixtures.materialize(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
