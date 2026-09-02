/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest.extract;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import de.codeministry.leadgen.config.model.SourcesConfig.Extraction;
import java.io.IOException;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Reads one offer out of one Markdown file: YAML frontmatter carries the fields, the body
 * is the description.
 *
 * <p>Unlike the newsletter, where one document holds a hundred offers, here one document
 * is one offer — so there is no block selector and nothing to count against. What makes
 * this worth a strategy of its own is that it stays deterministic: an offer typed by hand
 * needs no language model to be read, which keeps <i>rules before model</i> true for the
 * one path a person walks by hand.
 *
 * <p>The eight field names are the same contract as everywhere else. A frontmatter key
 * spelled differently is read and then ignored, in silence — which is the reason the
 * review screen exists.
 */
@Slf4j
@Component
public class MarkdownExtractor {

    /**
     * The whole document, not a prefix of it: the frontmatter is what lies between the
     * first `---` line and the next one, and everything after that is the body. Anchored
     * at the start, because a `---` in the middle of a pasted ad is a horizontal rule.
     */
    private static final Pattern FRONTMATTER =
            Pattern.compile("\\A\\uFEFF?---[ \\t]*\\r?\\n(.*?)\\r?\\n---[ \\t]*(?:\\r?\\n(.*))?\\z", Pattern.DOTALL);

    private final JsonMapper yaml = JsonMapper.builder(new YAMLFactory()).build();

    /**
     * @return one block, or none when the file has no frontmatter at all. A file without
     *     it is a pasted ad, which is what `fallback: llm` is for; until that exists the
     *     file is left where it is rather than entering as an offer with no title.
     */
    public List<Map<String, Object>> extract(String text, Extraction extraction) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Matcher matcher = FRONTMATTER.matcher(text);
        if (!matcher.matches()) {
            log.warn("A markdown document has no YAML frontmatter; nothing deterministic to read from it");
            return List.of();
        }

        Map<String, Object> front = parse(matcher.group(1));
        if (front == null) {
            return List.of();
        }

        Map<String, Object> block = new LinkedHashMap<>();
        for (var entry : front.entrySet()) {
            block.put(entry.getKey(), value(entry.getKey(), entry.getValue()));
        }

        String body = matcher.group(2);
        if (body != null && !body.isBlank()) {
            // The body wins over a `description:` key. Someone who writes both means the
            // prose they typed under the fence, not the one-liner above it.
            block.put(OfferMapper.DESCRIPTION, body.strip());
        }

        // The same privacy boundary as everywhere else: a file pasted out of the
        // newsletter carries the subscriber's address in every link, and it does not
        // matter that this document arrived by hand.
        var url = extraction.fields() == null ? null : extraction.fields().get(OfferMapper.URL);
        if (url != null && url.unwrapQueryParam() != null && block.get(OfferMapper.URL) instanceof String raw) {
            block.put(OfferMapper.URL, ProxyLink.unwrap(raw, url.unwrapQueryParam()));
        }
        return List.of(block);
    }

    private Map<String, Object> parse(String frontmatter) {
        try {
            Map<String, Object> parsed = yaml.readValue(frontmatter, new TypeReference<>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (IOException e) {
            // Not fatal, and not silent: the file stays on disk and the operator is told
            // which one could not be read.
            log.warn("Cannot read the frontmatter of a markdown document: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Everything reaches {@link OfferMapper} as text or as a list of text, because that is
     * what the eight-field contract is made of. YAML resolves a bare `2026-09-01` to a
     * date and `80` to a number, and `String.valueOf` on the first of those yields a form
     * no `date_format` describes.
     */
    private static Object value(String key, Object raw) {
        if (OfferMapper.TAGS.equals(key)) {
            return tags(raw);
        }
        return scalar(raw);
    }

    private static String scalar(Object raw) {
        return switch (raw) {
            case null -> null;
            case String text -> text.strip();
            case Date date -> date.toInstant().toString().substring(0, 10);
            case TemporalAccessor temporal -> temporal.toString();
            default -> String.valueOf(raw);
        };
    }

    /** A YAML list, or the comma-separated line someone typed instead. */
    private static List<String> tags(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> tags = new ArrayList<>();
            for (Object item : list) {
                String tag = scalar(item);
                if (tag != null && !tag.isBlank()) {
                    tags.add(tag);
                }
            }
            return tags;
        }
        String line = scalar(raw);
        if (line == null || line.isBlank()) {
            return List.of();
        }
        return Arrays.stream(line.split(","))
                .map(String::strip)
                .filter(tag -> !tag.isEmpty())
                .toList();
    }
}
