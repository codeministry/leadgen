/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.ConfigSource;
import de.codeministry.leadgen.config.ConfigValidationException;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.ChatModels;
import de.codeministry.leadgen.llm.Connections;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ISC-504: the chat's optional second connection in {@code pipeline.yaml}, and the {@code billed}
 * key on each connection.
 *
 * <p>The load cases go through the shipped file with its placeholders filled from a map, so what
 * is under test is the block that ships, not a fixture of its own. The one turn runs in a context
 * whose {@code pipeline.yaml} has the block cut out entirely, against the stub model, because
 * "absent runs the chat as before" is a claim about a real turn and not about a bound record.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ChatConnectionConfigTest {

    private static final ModelStub MODEL = ModelStub.start();

    /** Created once, never inside the property supplier — see {@code LlmBudgetTest}. */
    private static final Path CONFIG = withoutSecondConnection(MODEL.configuration());

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    /** A loopback address no test dials: the load cases only bind, they never call. */
    private static final String SOME_URL = "http://127.0.0.1:9/v1";

    private static final List<String> KINDS =
            List.of(ChatModels.OLLAMA, ChatModels.ANTHROPIC, ChatModels.OPENAI_COMPATIBLE);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = Databases.postgres();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG::toString);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConfigRegistry config;

    @TempDir
    Path configDir;

    @AfterAll
    static void stop() {
        MODEL.close();
        FACTORY.close();
    }

    @BeforeEach
    void reset() {
        MODEL.reset();
        ConfigFixtures.materialize(configDir);
        jdbc.update("DELETE FROM chat_conversation");
        jdbc.update("DELETE FROM chat_call_budget");
    }

    @Test
    void anUnknownProviderKindIsRefusedNamingTheKey() {
        assertThatThrownBy(() -> load(Map.of("CHAT_LLM_PROVIDER", "carrier-pigeon", "CHAT_LLM_BASE_URL", SOME_URL)))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("chat.connection.provider")
                .hasMessageContaining("carrier-pigeon");
    }

    @Test
    void aHostedKindWithoutABaseUrlIsRefusedNamingTheKey() {
        for (String hosted : List.of(ChatModels.ANTHROPIC, ChatModels.OPENAI_COMPATIBLE)) {
            assertThatThrownBy(() -> load(Map.of("CHAT_LLM_PROVIDER", hosted, "CHAT_LLM_API_KEY", "k")))
                    .as(hosted)
                    .isInstanceOf(ConfigValidationException.class)
                    .hasMessageContaining("chat.connection.base_url")
                    .hasMessageContaining(hosted);
        }
    }

    @Test
    void aBilledValueThatIsNeitherTrueNorFalseIsRefusedNamingTheKey() {
        assertThatThrownBy(() -> load(Map.of(
                        "CHAT_LLM_PROVIDER",
                        ChatModels.OLLAMA,
                        "CHAT_LLM_BASE_URL",
                        SOME_URL,
                        "CHAT_LLM_BILLED",
                        "maybe")))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("chat.connection.billed");
        assertThatThrownBy(() -> load(Map.of("LLM_BILLED", "yes")))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("llm.billed");
    }

    @Test
    void ollamaLoadsWithoutAKey() {
        PipelineConfig pipeline = load(Map.of("CHAT_LLM_PROVIDER", ChatModels.OLLAMA, "CHAT_LLM_BASE_URL", SOME_URL));

        Connections.Connection second = Connections.second(pipeline).orElseThrow();
        assertThat(second.name()).isEqualTo(Connections.Name.SECOND);
        assertThat(second.kind()).isEqualTo(ChatModels.OLLAMA);
        assertThat(second.llm().apiKey()).isNullOrEmpty();
        assertThat(second.llm().baseUrl()).isEqualTo(SOME_URL);
        assertThat(pipeline.chat().connection().maxCallsPerDay()).isEqualTo(50);
        assertThat(second.llm().timeout()).isEqualTo(PipelineConfig.Llm.DEFAULT_TIMEOUT);
        assertThat(Connections.all(pipeline))
                .extracting(Connections.Connection::name)
                .containsExactly(Connections.Name.PRIMARY, Connections.Name.SECOND);
    }

    @Test
    void theShippedBlockEmptyOrCutOutLeavesThePrimaryAlone() throws IOException {
        PipelineConfig empty = load(Map.of());
        assertThat(Connections.second(empty)).isEmpty();
        assertThat(Connections.all(empty))
                .extracting(Connections.Connection::name)
                .containsExactly(Connections.Name.PRIMARY);

        Path pipeline = configDir.resolve("pipeline.yaml");
        Files.writeString(pipeline, cut(Files.readString(pipeline, StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        PipelineConfig absent = load(Map.of());
        assertThat(absent.chat().connection()).isNull();
        assertThat(Connections.second(absent)).isEmpty();
    }

    @Test
    void billedIsReadAsSetAndWhenAbsentIsTrueForTheHostedKindOnly() {
        for (String kind : KINDS) {
            Map<String, Boolean> expected = new HashMap<>();
            expected.put("", ChatModels.ANTHROPIC.equals(kind));
            expected.put("true", true);
            expected.put("false", false);
            expected.forEach((billed, want) -> {
                Map<String, String> env = new HashMap<>();
                env.put("LLM_PROVIDER", kind);
                env.put("LLM_BASE_URL", SOME_URL);
                env.put("LLM_BILLED", billed);
                env.put("CHAT_LLM_PROVIDER", kind);
                env.put("CHAT_LLM_BASE_URL", SOME_URL);
                env.put("CHAT_LLM_BILLED", billed);
                PipelineConfig pipeline = load(env);

                assertThat(Connections.primary(pipeline).billed())
                        .as("llm, %s, billed '%s'", kind, billed)
                        .isEqualTo(want);
                assertThat(Connections.second(pipeline).orElseThrow().billed())
                        .as("chat.connection, %s, billed '%s'", kind, billed)
                        .isEqualTo(want);
            });
        }
    }

    @Test
    void theStubRewritesTheLlmBlockAndLeavesTheSecondConnectionAlone() throws IOException {
        String rewritten = Files.readString(MODEL.configuration().resolve("pipeline.yaml"), StandardCharsets.UTF_8);

        assertThat(rewritten).contains("  provider: openai-compatible", "  base_url: " + MODEL.baseUrl());
        assertThat(rewritten).contains("    provider: ${CHAT_LLM_PROVIDER:}", "    base_url: ${CHAT_LLM_BASE_URL:}");
    }

    /**
     * The shipped block, read the way {@code rg} would: every value a placeholder, and none of
     * them defaulting to a vendor, a URL or a model — only the timeout and the ceiling carry one.
     */
    @Test
    void everyShippedValueIsAPlaceholderWithAnEmptyDefault() {
        String shipped =
                ConfigSource.fromClasspath("pipeline.yaml").orElseThrow().content();

        assertThat(shipped).containsPattern("(?m)^  billed: \\$\\{LLM_BILLED:}$");
        List<String> values = connectionBlock(shipped);
        assertThat(values)
                .containsExactlyInAnyOrder(
                        "provider: ${CHAT_LLM_PROVIDER:}",
                        "base_url: ${CHAT_LLM_BASE_URL:}",
                        "api_key: ${CHAT_LLM_API_KEY:}",
                        "billed: ${CHAT_LLM_BILLED:}",
                        "timeout: ${CHAT_LLM_TIMEOUT:PT120S}",
                        "max_calls_per_day: ${CHAT_LLM_MAX_CALLS_PER_DAY:50}");
    }

    @Test
    void withTheBlockAbsentATurnIsAnsweredOnThePrimaryConnection() {
        assertThat(config.snapshot().application().chat().connection()).isNull();
        assertThat(Connections.all(config.snapshot().application()))
                .extracting(Connections.Connection::name)
                .containsExactly(Connections.Name.PRIMARY);
        MODEL.enqueue(ModelStub.text(java.time.Duration.ZERO, "On the primary."));
        long conversation =
                jdbc.queryForObject("INSERT INTO chat_conversation (title) VALUES ('') RETURNING id", Long.class);

        List<TurnStream.Event> events = TurnStream.ask(port, conversation, "Which connection?");

        assertThat(TurnStream.names(events).getLast()).isEqualTo("done");
        assertThat(TurnStream.text(events)).isEqualTo("On the primary.");
        assertThat(MODEL.bodies()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT model FROM chat_turn", String.class))
                .isEqualTo(ModelStub.MODEL);
    }

    private PipelineConfig load(Map<String, String> env) {
        return ConfigFixtures.loaderFor(configDir, VALIDATOR, env).load().application();
    }

    /** The lines under {@code chat.connection}, each trimmed and with its comment dropped. */
    private static List<String> connectionBlock(String yaml) {
        Matcher block =
                Pattern.compile("(?m)^  connection:\\R((?:(?: {4}.*)?\\R)*)").matcher(yaml);
        assertThat(block.find()).as("a chat.connection block ships").isTrue();
        List<String> values = new ArrayList<>();
        for (String line : block.group(1).split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                values.add(trimmed.replaceFirst("\\s+#.*$", ""));
            }
        }
        return values;
    }

    /** {@code yaml} with the {@code chat.connection} block cut out, comments and all. */
    static String cut(String yaml) {
        StringBuilder out = new StringBuilder();
        boolean skipping = false;
        for (String line : yaml.split("\n", -1)) {
            if (line.equals("  connection:")) {
                skipping = true;
                continue;
            }
            if (skipping && (line.startsWith("    ") || line.isBlank())) {
                continue;
            }
            skipping = false;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static Path withoutSecondConnection(Path dir) {
        try {
            Path pipeline = dir.resolve("pipeline.yaml");
            Files.writeString(
                    pipeline, cut(Files.readString(pipeline, StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
