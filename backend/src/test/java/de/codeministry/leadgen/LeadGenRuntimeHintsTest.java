/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.config.ConfigFixtures;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

/**
 * Every shipped default is reachable under a name computed at runtime.
 *
 * <p>Asserted against the directory rather than against a list, because a list is what fails:
 * the next `.yaml` or `.ftl` added under `src/main/resources/leadgen/` gets no hint, the JVM
 * image does not care, and the native one answers the empty `Optional` that also means "the
 * operator did not override this file". This test is the only place that difference is visible
 * without compiling an image.
 */
class LeadGenRuntimeHintsTest {

    @Test
    void everyShippedDefaultIsReachableByAComputedName() throws IOException {
        RuntimeHints hints = new RuntimeHints();
        new LeadGenRuntimeHints().registerHints(hints, getClass().getClassLoader());

        Path shipped = ConfigFixtures.repositoryRoot().resolve("backend/src/main/resources/leadgen");
        List<String> resources;
        try (Stream<Path> files = Files.walk(shipped)) {
            resources = files.filter(Files::isRegularFile)
                    .map(file -> shipped.getParent().relativize(file).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }

        // Guards the guard: a wrong path would make every assertion below vacuous.
        assertThat(resources).hasSizeGreaterThanOrEqualTo(7);
        assertThat(resources)
                .allSatisfy(
                        resource -> assertThat(RuntimeHintsPredicates.resource().forResource(resource))
                                .as(resource)
                                .accepts(hints));
    }

    /**
     * ISC-437: every tool the chat model reaches by name is invoked by reflection — Spring AI finds
     * the {@code @Tool} methods and calls them — and its result is written to JSON through its
     * record accessors. Neither has a literal for AOT to follow.
     *
     * <p>The tools are found by scanning for {@code @Tool} methods rather than read from the
     * registrar's list, so a sixth tool without a hint fails here, and so does removing one.
     */
    @Test
    void everyChatToolAndItsResultAreReachableByReflection() {
        RuntimeHints hints = new RuntimeHints();
        new LeadGenRuntimeHints().registerHints(hints, getClass().getClassLoader());

        List<Method> tools = toolMethods(Tool.class);
        // Guards the guard: a scan that finds nothing would make every assertion below vacuous.
        assertThat(tools).hasSizeGreaterThanOrEqualTo(5);
        for (Method tool : tools) {
            String name = tool.getDeclaringClass().getSimpleName() + "." + tool.getName();
            assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(tool))
                    .as(name + " has no invocation hint")
                    .accepts(hints);
            Set<Class<?>> results = new HashSet<>();
            ownRecords(tool.getGenericReturnType(), results);
            assertThat(results)
                    .as(name + " returns no record of this application")
                    .isNotEmpty();
            for (Class<?> result : results) {
                assertThat(RuntimeHintsPredicates.reflection().onType(result))
                        .as(result.getSimpleName() + ", returned by " + name + ", has no type hint")
                        .accepts(hints);
                for (RecordComponent component : result.getRecordComponents()) {
                    assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(component.getAccessor()))
                            .as(result.getSimpleName() + "." + component.getName() + "(), returned by " + name
                                    + ", cannot be read for JSON")
                            .accepts(hints);
                }
            }
        }
    }

    /**
     * ISC-489: the configuration binder validates every nested record of {@code leadgen.*} itself,
     * without an {@code @Valid} for AOT to follow, and Hibernate Validator reads a constrained
     * component through its field. Unhinted, the native image stops at startup on
     * {@code ConfigProperties$Chat.turnTimeout} (measured 2026-09-28). Every field of every nested
     * record is checked, so the next constrained one fails here rather than in the binary.
     */
    @Test
    void everyNestedConfigurationRecordCanBeValidated() {
        RuntimeHints hints = new RuntimeHints();
        new LeadGenRuntimeHints().registerHints(hints, getClass().getClassLoader());

        var nested = Arrays.stream(de.codeministry.leadgen.config.ConfigProperties.class.getRecordComponents())
                .map(RecordComponent::getType)
                .filter(Class::isRecord)
                .toList();
        assertThat(nested).isNotEmpty();
        for (Class<?> record : nested) {
            for (var field : record.getDeclaredFields()) {
                assertThat(RuntimeHintsPredicates.reflection().onFieldAccess(field))
                        .as(record.getSimpleName() + "." + field.getName() + " cannot be read for validation")
                        .accepts(hints);
            }
        }
    }

    /**
     * ISC-489: the MCP server finds the {@code @McpTool} methods by reflection and calls them, and
     * writes each result to JSON through its accessors, the sealed {@code FunnelStats}' subtypes
     * included, which no signature names. Scanned like the chat's tools, so an eleventh tool without
     * a hint fails here.
     *
     * <p>{@code leadgen_get_pipeline_config} returns {@code Object}: its four sections are the
     * views the REST controllers return, which Spring's AOT processing binds from there. It is the
     * one tool allowed to name no record of its own.
     */
    @Test
    void everyMcpToolAndItsResultAreReachableByReflection() {
        RuntimeHints hints = new RuntimeHints();
        new LeadGenRuntimeHints().registerHints(hints, getClass().getClassLoader());

        List<Method> tools = toolMethods(McpTool.class);
        assertThat(tools).hasSize(10);
        for (Method tool : tools) {
            String name = tool.getDeclaringClass().getSimpleName() + "." + tool.getName();
            assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(tool))
                    .as(name + " has no invocation hint")
                    .accepts(hints);
            Set<Class<?>> results = new HashSet<>();
            ownRecords(tool.getGenericReturnType(), results);
            if (tool.getReturnType() != Object.class) {
                assertThat(results)
                        .as(name + " returns no record of this application")
                        .isNotEmpty();
            }
            for (Class<?> result : results) {
                assertThat(RuntimeHintsPredicates.reflection().onType(result))
                        .as(result.getSimpleName() + ", returned by " + name + ", has no type hint")
                        .accepts(hints);
                for (RecordComponent component : result.getRecordComponents()) {
                    assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(component.getAccessor()))
                            .as(result.getSimpleName() + "." + component.getName() + "(), returned by " + name
                                    + ", cannot be read for JSON")
                            .accepts(hints);
                }
            }
        }
    }

    /** ISC-489: every record of the mcp package goes to JSON by reflection, as the chat's do. */
    @Test
    void everyMcpRecordIsBoundForJson() {
        RuntimeHints hints = new RuntimeHints();
        new LeadGenRuntimeHints().registerHints(hints, getClass().getClassLoader());

        List<Class<?>> records = records("de.codeministry.leadgen.mcp");
        assertThat(records).hasSizeGreaterThanOrEqualTo(8);
        for (Class<?> record : records) {
            assertThat(RuntimeHintsPredicates.reflection().onType(record))
                    .as(record.getName() + " has no type hint")
                    .accepts(hints);
            for (RecordComponent component : record.getRecordComponents()) {
                assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(component.getAccessor()))
                        .as(record.getName() + "." + component.getName() + "() cannot be read for JSON")
                        .accepts(hints);
            }
        }
    }

    /**
     * Finding 7: every record of the chat package is read or written by Jackson through reflection —
     * the SSE events, the conversation views, the ledger's citations in {@code chat_turn.citations},
     * the tool results — and none of it is a call AOT can follow. Scanned rather than listed, so a
     * new record without a hint fails here; private records never leave their class and are exempt.
     */
    @Test
    void everyChatRecordIsBoundForJson() {
        RuntimeHints hints = new RuntimeHints();
        new LeadGenRuntimeHints().registerHints(hints, getClass().getClassLoader());

        List<Class<?>> records = records("de.codeministry.leadgen.chat");
        // Guards the guard: the chat package holds more than a dozen records today.
        assertThat(records).hasSizeGreaterThanOrEqualTo(12);
        for (Class<?> record : records) {
            assertThat(RuntimeHintsPredicates.reflection().onType(record))
                    .as(record.getName() + " has no type hint")
                    .accepts(hints);
            for (RecordComponent component : record.getRecordComponents()) {
                assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(component.getAccessor()))
                        .as(record.getName() + "." + component.getName() + "() cannot be read for JSON")
                        .accepts(hints);
            }
        }
    }

    private static List<Class<?>> records(String pkg) {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(
                    org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
                return true;
            }
        };
        scanner.addIncludeFilter((reader, factory) -> true);
        List<Class<?>> records = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(pkg)) {
            try {
                Class<?> type = Class.forName(candidate.getBeanClassName());
                // The test tree shares the package; its helpers never reach a native image.
                boolean shipped = !type.getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .getPath()
                        .contains("/test/");
                if (shipped && type.isRecord() && !java.lang.reflect.Modifier.isPrivate(type.getModifiers())) {
                    records.add(type);
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return records;
    }

    private static List<Method> toolMethods(Class<? extends java.lang.annotation.Annotation> annotation) {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(
                (reader, factory) -> reader.getAnnotationMetadata().hasAnnotatedMethods(annotation.getName()));
        List<Method> methods = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("de.codeministry.leadgen")) {
            try {
                Arrays.stream(Class.forName(candidate.getBeanClassName()).getDeclaredMethods())
                        .filter(method -> method.isAnnotationPresent(annotation))
                        .forEach(methods::add);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return methods;
    }

    /** The records of this application a type reaches, through record components and type arguments,
     * and through the permitted subtypes of a sealed interface. */
    private static void ownRecords(Type type, Set<Class<?>> found) {
        if (type instanceof ParameterizedType parameterized) {
            ownRecords(parameterized.getRawType(), found);
            for (Type argument : parameterized.getActualTypeArguments()) {
                ownRecords(argument, found);
            }
        } else if (type instanceof Class<?> sealed
                && sealed.isSealed()
                && sealed.getName().startsWith("de.codeministry.leadgen.")) {
            for (Class<?> permitted : sealed.getPermittedSubclasses()) {
                ownRecords(permitted, found);
            }
        } else if (type instanceof Class<?> raw
                && raw.isRecord()
                && raw.getName().startsWith("de.codeministry.leadgen.")
                && found.add(raw)) {
            for (RecordComponent component : raw.getRecordComponents()) {
                ownRecords(component.getGenericType(), found);
            }
        }
    }
}
