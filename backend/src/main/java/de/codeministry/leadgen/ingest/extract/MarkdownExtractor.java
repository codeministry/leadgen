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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *
 * <p><b>A document with no frontmatter is the {@code fallback} case</b>, and what happens
 * to it is the source's decision, not this class's: {@code none} leaves it where it is,
 * {@code llm} hands it to {@link LlmExtractor}. The order is what keeps <i>rules before
 * model</i> true — the model is asked about the one shape the rules cannot address, and
 * only after they have found nothing.
 */
@Slf4j
@Component
public class MarkdownExtractor {

    /**
     * The two values {@code extraction.fallback} may take. Anything else is refused by name
     * rather than approximated: a source configured with a fallback this does not know would
     * otherwise behave exactly like one configured with none.
     */
    public static final String NONE = "none";

    public static final String LLM = "llm";

    /**
     * The whole document, not a prefix of it: the frontmatter is what lies between the
     * first `---` line and the next one, and everything after that is the body. Anchored
     * at the start, because a `---` in the middle of a pasted ad is a horizontal rule.
     */
    private static final Pattern FRONTMATTER =
            Pattern.compile("\\A\\uFEFF?---[ \\t]*\\r?\\n(.*?)\\r?\\n---[ \\t]*(?:\\r?\\n(.*))?\\z", Pattern.DOTALL);

    private final JsonMapper yaml = JsonMapper.builder(new YAMLFactory()).build();

    private final ExtractionFallback fallback;

    public MarkdownExtractor(ExtractionFallback fallback) {
        this.fallback = fallback;
    }

    /**
     * The blocks alone, for the pipeline, which has nowhere to put anything else.
     *
     * @return one block, or none. A file with no frontmatter at all is a pasted ad, and
     * what becomes of it is the source's {@code fallback}: under {@code none} it is left
     * where it is rather than entering as an offer with no title, under {@code llm} it is
     * read by a model and still reviewed before it becomes an offer.
     */
    public List<Map<String, Object>> extract(String text, Extraction extraction) {
        return read(text, extraction).blocks();
    }

    /**
     * The blocks and where their values came from.
     *
     * <p>The second half exists for the review screen and for nothing else: an upload is
     * reviewed before it becomes an offer, and a reviewer who cannot see which values a
     * model read has to check all of them equally. It is deliberately not part of a block —
     * a block is the eight-field contract the whole pipeline is built on, and provenance in
     * it would travel all the way into the archive.
     */
    public Document read(String text, Extraction extraction) {
        if (text == null || text.isBlank()) {
            return Document.nothing();
        }
        Matcher matcher = FRONTMATTER.matcher(text);
        if (!matcher.matches()) {
            return withoutFrontmatter(text, extraction);
        }

        Map<String, Object> front = parse(matcher.group(1));
        if (front == null) {
            return Document.nothing();
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

        return Document.byTheRules(unwrapped(block, extraction));
    }

    /**
     * The {@code fallback} case, and every way out of it is a sentence in the log. A
     * document that quietly produces no offer is the failure this stage is most likely to
     * have, and the reasons are not interchangeable — the two that are about the model
     * rather than about the configuration are written one class further down.
     */
    private Document withoutFrontmatter(String text, Extraction extraction) {
        String configured = extraction == null ? null : extraction.fallback();
        if (configured == null || configured.isBlank() || NONE.equalsIgnoreCase(configured)) {
            log.warn("A markdown document has no YAML frontmatter; nothing deterministic to read from it");
            return Document.nothing();
        }
        if (!LLM.equalsIgnoreCase(configured)) {
            log.warn(
                "A markdown document has no YAML frontmatter and the source asks for fallback '{}';"
                    + " implemented are '{}' and '{}'",
                configured,
                NONE,
                LLM);
            return Document.nothing();
        }
        return fallback.read(text)
            .map(reading -> new Document(
                List.of(unwrapped(reading.block(), extraction)), List.copyOf(reading.fromModel())))
            .orElseGet(Document::nothing);
    }

    /**
     * The same privacy boundary as everywhere else: a file pasted out of the newsletter
     * carries the subscriber's address in every link, and it does not matter that this
     * document arrived by hand — nor that a model was the one that found the link.
     */
    private static Map<String, Object> unwrapped(Map<String, Object> block, Extraction extraction) {
        var url = extraction == null || extraction.fields() == null
            ? null
            : extraction.fields().get(OfferMapper.URL);
        if (url != null && url.unwrapQueryParam() != null && block.get(OfferMapper.URL) instanceof String raw) {
            block.put(OfferMapper.URL, ProxyLink.unwrap(raw, url.unwrapQueryParam()));
        }
        return block;
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

    /**
     * A YAML list, or the comma-separated line someone typed instead.
     */
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

    /**
     * What one document yielded, and which of its values a model read.
     *
     * @param blocks    what the pipeline maps into offers. One at most here — one document is
     *                  one offer, unlike the newsletter.
     * @param fromModel the field names in {@code blocks} that came from the fallback, empty
     *                  whenever the frontmatter was readable. A frontmatter body and a pasted
     *                  advert are both the document's own words, so neither is in here.
     */
    public record Document(List<Map<String, Object>> blocks, List<String> fromModel) {

        static Document nothing() {
            return new Document(List.of(), List.of());
        }

        static Document byTheRules(Map<String, Object> block) {
            return new Document(List.of(block), List.of());
        }
    }
}
