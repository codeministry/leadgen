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

import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.analytics.AnalyticsQueryService;
import de.codeministry.leadgen.analytics.AnalyticsSummaryQueryService;
import de.codeministry.leadgen.analytics.LastRunQueryService;
import de.codeministry.leadgen.application.ApplicationService;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.offer.OfferQueryService;
import de.codeministry.leadgen.packaging.CoverLetterService;
import de.codeministry.leadgen.retrieval.QueryEmbedder;
import de.codeministry.leadgen.retrieval.SemanticFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ISC-428: no tool the model can call writes.
 *
 * <p>Two halves, because either alone can be fooled. The static half reflects over every class in
 * the application that carries a {@code @Tool} method — found by scanning, not listed, so a sixth
 * tool is checked the day it is added — and holds each constructor dependency against an allowlist
 * of read services. Two services on it also write ({@link ApplicationService} and
 * {@link CoverLetterService}), and one tool holds a {@link JdbcClient}; for those the tool's own
 * bytecode is read and every method it invokes on them must be a read. The dynamic half runs a real
 * turn that calls all five tools and compares every non-chat table's row count and content before
 * and after, which catches a write reached through a read service's own dependencies.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ChatToolsReadOnlyTest {

    /** Services whose tool-facing surface reads, and nothing else a tool may be handed. */
    private static final Set<Class<?>> READ_SERVICES = Set.of(
            OfferQueryService.class,
            AnalyticsQueryService.class,
            AnalyticsSummaryQueryService.class,
            LastRunQueryService.class,
            SemanticFilter.class,
            QueryEmbedder.class,
            // Writes only `chat_call_budget`, the chat's own table: the semantic search pays its
            // embedding request from the chat's day rather than the pipeline's (ISC-433).
            ChatBudget.class,
            ConfigRegistry.class,
            ApplicationService.class,
            CoverLetterService.class,
            JdbcClient.class);

    /**
     * For a dependency on the allowlist that can also write: the only methods a tool may invoke
     * on it. The owner is matched by prefix so {@code JdbcClient$StatementSpec} counts as JDBC.
     */
    private static final Map<String, Set<String>> READ_METHODS = Map.of(
            Type.getInternalName(ApplicationService.class), Set.of("findByOffer", "history", "find"),
            Type.getInternalName(CoverLetterService.class), Set.of("read"),
            Type.getInternalName(JdbcClient.class), Set.of("sql", "param", "query", "list", "optional", "single"));

    private static final ModelStub MODEL = ModelStub.start();

    /** Created once, never inside the property supplier — see {@code LlmBudgetTest}. */
    private static final Path CONFIG = MODEL.configuration();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG::toString);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @BeforeEach
    void reset() {
        MODEL.reset();
        jdbc.update("DELETE FROM chat_conversation");
        jdbc.update("DELETE FROM chat_call_budget");
    }

    @Test
    void everyToolDependsOnReadServicesOnly() {
        List<Class<?>> tools = toolClasses();
        // Guards the guard: a scan that finds nothing would pass every assertion below.
        assertThat(tools).hasSizeGreaterThanOrEqualTo(5);

        for (Class<?> tool : tools) {
            for (Constructor<?> constructor : tool.getDeclaredConstructors()) {
                for (Class<?> dependency : constructor.getParameterTypes()) {
                    assertThat(dependency.getSimpleName())
                            .as(tool.getSimpleName() + " depends on a repository")
                            .doesNotEndWith("Repository");
                    assertThat(dependency.isAnnotationPresent(Repository.class))
                            .as(tool.getSimpleName() + " depends on @Repository " + dependency.getName())
                            .isFalse();
                    assertThat(READ_SERVICES)
                            .as(tool.getSimpleName() + " depends on " + dependency.getName()
                                    + ", which is not on the read-service allowlist")
                            .contains(dependency);
                }
            }
        }
    }

    @Test
    void aToolInvokesOnlyTheReadMethodsOfAServiceThatAlsoWrites() {
        for (Class<?> tool : toolClasses()) {
            for (String[] call : invocations(tool)) {
                String owner = call[0];
                String name = call[1];
                READ_METHODS.forEach((service, allowed) -> {
                    if (owner.equals(service) || owner.startsWith(service + "$")) {
                        assertThat(allowed)
                                .as(tool.getSimpleName() + " invokes " + owner + "." + name)
                                .contains(name);
                    }
                });
            }
        }
    }

    @Test
    void aTurnCallingEveryToolWritesNothingOutsideTheChatsTables() {
        long source =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('ro', 'file') RETURNING id", Long.class);
        long offer = jdbc.queryForObject("""
                INSERT INTO offer (source_id, external_id, title, description, url, fingerprint, status,
                                   score_value, portal, ingested_at)
                VALUES (?, 'ro-1', 'Kafka Developer', 'Event streaming.', 'https://example.invalid/ro', 'ro-1',
                        'PASSED', 80, 'portal-a', now())
                RETURNING id
                """, Long.class, source);
        long application = jdbc.queryForObject(
                "INSERT INTO application (offer_id, status, note) VALUES (?, 'SENT', 'called twice') RETURNING id",
                Long.class,
                offer);
        jdbc.update(
                "INSERT INTO application_event (application_id, from_status, to_status, note)"
                        + " VALUES (?, 'PACKAGED', 'SENT', 'by mail')",
                application);
        MODEL.enqueue(ModelStub.toolCalls(
                "search_offers", "{\"text\":\"kafka\"}",
                "search_by_meaning", "{\"query\":\"event streaming\"}",
                "statistics", "{}",
                "application", "{\"offerId\":" + offer + "}",
                "profile", "{}"));
        MODEL.enqueue(ModelStub.text(Duration.ofMillis(5), "Read ", "everything."));
        long conversation =
                jdbc.queryForObject("INSERT INTO chat_conversation (title) VALUES ('') RETURNING id", Long.class);
        Map<String, String> before = nonChatTables();

        List<TurnStream.Event> events = TurnStream.ask(port, conversation, "Tell me everything");

        // The premise: all five tools ran and the turn finished.
        assertThat(TurnStream.names(events).getLast()).isEqualTo("done");
        assertThat(jdbc.queryForList("SELECT tool FROM chat_tool_call ORDER BY ordinal", String.class))
                .containsExactly("search_offers", "search_by_meaning", "statistics", "application", "profile");
        assertThat(before).containsKeys("offer", "application", "application_event", "source", "llm_call_budget");

        assertThat(nonChatTables()).isEqualTo(before);
    }

    /** Every non-chat table, as its row count and a digest of its rows. */
    private Map<String, String> nonChatTables() {
        Map<String, String> tables = new LinkedHashMap<>();
        for (String table : jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
                   AND table_name NOT LIKE 'chat\\_%' AND table_name <> 'flyway_schema_history'
                 ORDER BY table_name
                """, String.class)) {
            tables.put(
                    table,
                    jdbc.queryForObject(
                            "SELECT count(*) || ':' || coalesce(md5(string_agg(t::text, '|' ORDER BY t::text)), '')"
                                    + " FROM \"" + table + "\" t",
                            String.class));
        }
        return tables;
    }

    /** Every application class with a {@code @Tool} method, by scanning rather than by a list. */
    static List<Class<?>> toolClasses() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(
                (reader, factory) -> reader.getAnnotationMetadata().hasAnnotatedMethods(Tool.class.getName()));
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("de.codeministry.leadgen")) {
            try {
                classes.add(Class.forName(candidate.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        classes.removeIf(type -> Arrays.stream(type.getDeclaredMethods()).noneMatch(ChatToolsReadOnlyTest::isTool));
        return classes;
    }

    private static boolean isTool(Method method) {
        return method.isAnnotationPresent(Tool.class);
    }

    /** Every {@code owner, name} the class's bytecode invokes, lambdas included. */
    private static List<String[]> invocations(Class<?> type) {
        List<String[]> calls = new ArrayList<>();
        try (InputStream in = type.getClassLoader().getResourceAsStream(Type.getInternalName(type) + ".class")) {
            new ClassReader(in)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public MethodVisitor visitMethod(
                                        int access,
                                        String name,
                                        String descriptor,
                                        String signature,
                                        String[] exceptions) {
                                    return new MethodVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visitMethodInsn(
                                                int opcode, String owner, String method, String desc, boolean itf) {
                                            calls.add(new String[] {owner, method});
                                        }
                                    };
                                }
                            },
                            ClassReader.SKIP_DEBUG);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Guards the guard: a class read as empty would pass every assertion.
        assertThat(calls).as(type.getSimpleName() + " bytecode").isNotEmpty();
        return calls;
    }
}
