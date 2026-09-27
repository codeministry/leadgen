/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.ConfigSnapshot;
import de.codeministry.leadgen.config.Secrets;
import de.codeministry.leadgen.config.model.SourcesConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

/**
 * The one place every tool result passes before the model reads it.
 *
 * <p><b>What the model must never read</b>: the configured mailbox address — an offer text, a
 * profile field or an event note can carry it as easily as the configuration can — and every value
 * {@link Secrets} would mask in the startup banner. The same rule as the banner, so there is one
 * answer to "is this a secret": the name it is configured under decides, not the value.
 *
 * <p><b>Values, not patterns.</b> The masker replaces the values this instance is actually
 * configured with, collected once per configuration snapshot because the configuration reloads: each connection's
 * username and password from {@code sources.yaml}, every property whose name {@link Secrets#isSecret},
 * and every property whose value is an e-mail address. Every other e-mail address in an advert
 * stays, because a recruiter's address is what the person asked about. URL credentials go wherever
 * they appear.
 *
 * <p><b>Not every login is a secret.</b> A property named {@code …user} or {@code USER} is no reason
 * on its own: the database login and the shell's user are ordinary words, and masked as values they
 * turned every "PostgreSQL" in an advert into "***QL". And a value that is no address is masked only
 * as a whole word, so a secret that happens to be a common word cannot eat the longer words that
 * contain it; an address is masked wherever it appears, since nothing else contains one.
 *
 * <p><b>Inside the JSON, not across it.</b> A JSON result is parsed and only its strings are
 * rewritten, so a secret that happens to read {@code 7} cannot turn an id into a mask and the model
 * still gets a valid document. Text that is no JSON is masked as text.
 */
@Component
@RequiredArgsConstructor
public class ToolOutputMasker {

    /**
     * Shorter values are not masked: a two-letter secret would blank out every word it occurs in
     * and protect nothing a guess would not reveal.
     */
    static final int SHORTEST_MASKED = 4;

    /** A value that is one e-mail address and nothing else. */
    private static final Pattern ADDRESS = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");

    /** What a whole-word match must not have on either side: a letter, a digit or an underscore. */
    private static final String WORD = "[\\p{L}\\p{N}_]";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ConfigRegistry config;
    private final Environment environment;

    /**
     * The pattern built for the snapshot it was built from. Enumerating every property source and
     * compiling a new alternation on each tool call was work repeated for an answer that only
     * changes when {@link ConfigRegistry#reload} swaps the snapshot, so the snapshot's identity is
     * the key: a reload hands out a new object, and the next call rebuilds.
     */
    private final AtomicReference<Values> cache = new AtomicReference<>();

    /** A snapshot and the pattern of its values; {@code pattern} is null when there is nothing to mask. */
    private record Values(ConfigSnapshot snapshot, Pattern pattern) {}

    /**
     * @param toolResult what a tool returned, as the model will read it — usually JSON.
     * @return the same text with every configured address and secret replaced by {@link Secrets#MASK}.
     */
    public String mask(String toolResult) {
        if (toolResult == null || toolResult.isEmpty()) {
            return toolResult;
        }
        Pattern values = values();
        JsonNode tree = parse(toolResult);
        if (tree == null || !tree.isContainerNode()) {
            return replace(toolResult, values);
        }
        JsonNode masked = walk(tree.deepCopy(), values);
        if (masked.equals(tree)) {
            // Nothing to hide: hand back the tool's own bytes rather than a re-serialisation.
            return toolResult;
        }
        try {
            return JSON.writeValueAsString(masked);
        } catch (JsonProcessingException e) {
            // Unreachable for a tree this parser built; masking the text is the safe answer anyway.
            return replace(toolResult, values);
        }
    }

    private JsonNode walk(JsonNode node, Pattern values) {
        if (node.isTextual()) {
            return TextNode.valueOf(replace(node.textValue(), values));
        }
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                object.set(name, walk(object.get(name), values));
            }
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                array.set(index, walk(array.get(index), values));
            }
        }
        return node;
    }

    private static String replace(String text, Pattern values) {
        String masked = Secrets.maskUrlCredentials(text);
        return values == null ? masked : values.matcher(masked).replaceAll(Matcher.quoteReplacement(Secrets.MASK));
    }

    /**
     * One alternation, longest value first so a password containing a shorter secret goes whole.
     * Case-insensitive: an address is the same address in capitals, and over-masking is the safe
     * direction to be wrong in. An address matches anywhere; every other value only as a whole
     * word, see the class comment.
     */
    private static Pattern pattern(Set<String> values) {
        List<String> sorted = values.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(value -> isAddress(value)
                        ? Pattern.quote(value)
                        : "(?<!" + WORD + ")" + Pattern.quote(value) + "(?!" + WORD + ")")
                .toList();
        return sorted.isEmpty()
                ? null
                : Pattern.compile(String.join("|", sorted), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** The current snapshot's pattern, built once per snapshot; two threads racing build it twice, harmlessly. */
    private Pattern values() {
        ConfigSnapshot snapshot = config.snapshot();
        Values cached = cache.get();
        if (cached == null || cached.snapshot() != snapshot) {
            cached = new Values(snapshot, pattern(sensitiveValues(snapshot)));
            cache.set(cached);
        }
        return cached.pattern();
    }

    private Set<String> sensitiveValues(ConfigSnapshot snapshot) {
        Set<String> values = new LinkedHashSet<>();
        SourcesConfig sources = snapshot.sources();
        if (sources != null) {
            for (SourcesConfig.Connection connection : sources.connections()) {
                add(values, connection.username());
                add(values, connection.password());
            }
        }
        for (String name : propertyNames()) {
            String value = safely(name);
            if (Secrets.isSecret(name) || isAddress(value)) {
                add(values, value);
            }
        }
        return values;
    }

    private static boolean isAddress(String value) {
        return value != null && ADDRESS.matcher(value.strip()).matches();
    }

    private Set<String> propertyNames() {
        Set<String> names = new LinkedHashSet<>();
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return names;
        }
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                names.addAll(Arrays.asList(enumerable.getPropertyNames()));
            }
        }
        return names;
    }

    /** A property whose placeholder cannot resolve is not a value anybody could leak. */
    private String safely(String name) {
        try {
            return environment.getProperty(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A flag under a secret-sounding name ({@code …token-required: true}) is no secret, and masking
     * the word would blank it out of every advert that uses it.
     */
    private static void add(Set<String> values, String value) {
        if (value == null) {
            return;
        }
        String stripped = value.strip();
        if (stripped.length() >= SHORTEST_MASKED
                && !stripped.equalsIgnoreCase("true")
                && !stripped.equalsIgnoreCase("false")) {
            values.add(stripped);
        }
    }

    private static JsonNode parse(String text) {
        try {
            return JSON.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
