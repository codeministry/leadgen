/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.llm.Answers;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Reads one offer out of a document that has no frontmatter to read — the {@code fallback:
 * llm} case, and the only place in ingest where a language model sees a document at all.
 *
 * <p>This is the exception that proves <i>rules before model</i> rather than breaking it.
 * Every structured source is read by CSS or by frontmatter and costs nothing; what arrives
 * here is a pasted advert, where there is no structure to address and the alternative is
 * not a cheaper reading but no offer at all.
 *
 * <p><b>The description is never the model's.</b> It is the document, verbatim, because a
 * model asked for a description writes a summary — and a summary is what the enrichment
 * stage, the classifier and the judge would then all be reading instead of the advert.
 * What the model is asked for is the seven short fields around it.
 *
 * <p>Two of those seven are checked against the document before they are kept, and both
 * are the kind of mistake that is invisible on the review screen:
 * <ul>
 *   <li>a {@code url} that is not in the document character for character is an address the
 *       model assembled out of a portal's name, and it leads somewhere real,
 *   <li>a {@code published} date the model cannot quote a line for is a date it inferred,
 *       and this one field decides what the freshness rule calls old.
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class LlmExtractor {

    /**
     * A title is a headline, not the first paragraph of the advert.
     */
    static final int MAX_TITLE = 200;

    /**
     * Location, portal and agency are each a few words on a card.
     */
    static final int MAX_VALUE = 120;

    static final int MAX_TAG = 40;

    static final int MAX_TAGS = 12;

    /**
     * The quote a date is read from. The same bound {@code fields.FieldExtractor} puts on
     * its phrases, and for the same reason: it is a line of the advert, not a page of it.
     */
    static final int MAX_QUOTE = 200;

    /**
     * The window a real publication date falls in. The lower end rejects the epoch date a
     * model answers when it has nothing; the upper end is today, because an advert cannot
     * have been published tomorrow and a date in the future is permanently fresh.
     */
    static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);

    private static final String INSTRUCTIONS = """
            You are reading one freelance project advert that somebody pasted into a file.
            There is no structure to rely on: it may be a mail, a portal page copied by hand,
            or a few lines out of a chat.

            Pull these seven things out of it and nothing else:

            TITLE      the advert's own headline, the role as it names it
            URL        the address of the advert, only when the document contains one
            LOCATION   where the work happens, as the advert puts it
            PORTAL     the site, newsletter or mailing list the advert came from
            AGENCY     the company or recruiter behind it; when both an agency and its client
                       are named, the agency is the one writing
            PUBLISHED  when the advert was published
            TAGS       the technologies and skills it asks for

            Rules:
            - Answer null for anything the document does not state. Most pasted adverts name
              no portal and no publication date, and null is a normal answer, not a failure.
            - Never translate, never tidy up and never summarise. Every value is the
              document's own wording, in the document's own language.
            - TITLE is the one field that has to be there. A document you cannot find a role
              in is not an advert, and an offer with no title is worse than no offer.
            - URL is copied out of the document character for character. An address you
              assembled from a portal's name is discarded, and it leads somewhere real.
            - PUBLISHED comes with the line you read it from. The "text" is a quote from the
              document; a date you cannot quote is a date you inferred, and it is discarded.
              "date" is ISO, YYYY-MM-DD.
            - TAGS are the words the advert uses, not a category you chose for it. At most
              twelve, and never a skill it does not ask for.
            - Do not write a description. The advert itself is kept as it stands, and a
              summary would replace it.

            Answer only with JSON of this shape, and nothing else:
            {"title":"Senior Java Entwickler (m/w/d)",
             "url":"https://jobs.example.com/p/8831",
             "location":"Remote, gelegentlich vor Ort",
             "portal":"jobs.example.com",
             "agency":"Beispiel GmbH",
             "published":{"text":"eingestellt am 01.10.2026","date":"2026-10-01"},
             "tags":["Java","Spring Boot","Kubernetes"]}
            """;

    private final ChatModel chatModel;
    private final String model;
    private final ObjectMapper json;
    private final Clock clock;

    public String model() {
        return model;
    }

    /**
     * The system prompt, for the Rules screen. Nothing is substituted into it: the question
     * is about what a document says, not about who is applying.
     */
    public static String instructions() {
        return INSTRUCTIONS;
    }

    /**
     * The shape a document arrives in, built by the same method the real call uses.
     */
    public static String exampleUser() {
        return describe("<the pasted advert, exactly as the file holds it>");
    }

    /**
     * One block of fields, keyed the way {@link OfferMapper} expects them, or nothing.
     *
     * <p>Nothing means the model did not answer or answered something unusable, and the
     * caller treats it exactly as it treats a document with no frontmatter: the file stays
     * where it is. Nothing is thrown — one unreachable endpoint must not end a run.
     */
    public Optional<Reading> read(String document) {
        if (document == null || document.isBlank()) {
            return Optional.empty();
        }
        String content = null;
        try {
            ChatResponse response = ChatClient.create(chatModel)
                    .prompt()
                    .system(INSTRUCTIONS)
                    .user(describe(document))
                    .call()
                    .chatResponse();
            content = Answers.textOf(response);
            return build(document, json.readTree(Answers.objectIn(content)));
        } catch (RuntimeException e) {
            log.warn("The extraction fallback failed for a document with no frontmatter: {}", e.getMessage());
            return Optional.empty();
        } catch (IOException e) {
            log.warn(
                    "The extraction fallback did not answer with usable JSON. It said: {}",
                    Answers.abbreviate(content));
            return Optional.empty();
        }
    }

    /**
     * The document as the model sees it. A label and the text, because a bare document
     * reaching a chat endpoint as the whole user message reads as an instruction to whatever
     * the advert happens to say in its first line.
     */
    static String describe(String document) {
        return "Document:\n" + document;
    }

    /**
     * The answer, with every value checked on this side.
     *
     * <p>A field that fails its check is dropped and the rest is kept, the same rule the
     * field extractor follows: a model that is wrong about the portal is not wrong about
     * the advert.
     */
    private Optional<Reading> build(String document, JsonNode parsed) {
        String title = text(parsed.path(OfferMapper.TITLE), MAX_TITLE);
        if (title == null) {
            // Not a warning about the model: a document that holds no role is the case this
            // fallback exists to recognise, and it says so rather than inventing one.
            log.info("The extraction fallback found no title in a document; it is left where it is");
            return Optional.empty();
        }

        Map<String, Object> block = new LinkedHashMap<>();
        Set<String> fromModel = new LinkedHashSet<>();
        put(block, fromModel, OfferMapper.TITLE, title);
        put(block, fromModel, OfferMapper.LOCATION, text(parsed.path(OfferMapper.LOCATION), MAX_VALUE));
        put(block, fromModel, OfferMapper.PORTAL, text(parsed.path(OfferMapper.PORTAL), MAX_VALUE));
        put(block, fromModel, OfferMapper.AGENCY, text(parsed.path(OfferMapper.AGENCY), MAX_VALUE));
        put(block, fromModel, OfferMapper.URL, url(document, parsed.path(OfferMapper.URL)));
        put(block, fromModel, OfferMapper.PUBLISHED, published(document, parsed.path(OfferMapper.PUBLISHED)));

        List<String> tags = tags(parsed.path(OfferMapper.TAGS));
        if (!tags.isEmpty()) {
            block.put(OfferMapper.TAGS, tags);
            fromModel.add(OfferMapper.TAGS);
        }

        // The document, not a reading of it. Deliberately outside `fromModel`: this half is
        // as deterministic as a frontmatter body, and the review screen should not mark it
        // as something that needs checking against itself.
        block.put(OfferMapper.DESCRIPTION, document.strip());
        return Optional.of(new Reading(block, Set.copyOf(fromModel)));
    }

    private static void put(Map<String, Object> block, Set<String> fromModel, String key, String value) {
        if (value != null) {
            block.put(key, value);
            fromModel.add(key);
        }
    }

    /**
     * An address the document actually contains. The check is the whole point of the field:
     * a model that cannot find a link writes a plausible one, and a plausible link on the
     * review screen looks exactly like a real one until it is clicked.
     */
    private static String url(String document, JsonNode node) {
        String url = text(node, 2048);
        if (url == null) {
            return null;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            log.info("The extraction fallback answered a url that is not one: {}", Answers.abbreviate(url));
            return null;
        }
        if (!document.contains(url)) {
            log.info("The extraction fallback answered a url the document does not contain; dropping it");
            return null;
        }
        return url;
    }

    /**
     * An ISO date, but only with a line of the document behind it and only in the window a
     * real publication date falls in.
     */
    private String published(String document, JsonNode node) {
        String quote = text(node.path("text"), MAX_QUOTE);
        String value = text(node.path("date"), 40);
        if (quote == null || value == null) {
            return null;
        }
        if (!normalised(document).contains(normalised(quote))) {
            log.info("The extraction fallback quoted a line the document does not hold; dropping the date");
            return null;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            log.info("The extraction fallback answered a published date that is not ISO: {}", value);
            return null;
        }
        if (date.isBefore(EARLIEST) || date.isAfter(LocalDate.now(clock))) {
            // A future date is the expensive half: `published` is what the freshness rule
            // counts days from, so one would stay new for as long as it exists.
            log.info("The extraction fallback answered a published date outside the window: {}", date);
            return null;
        }
        return date.toString();
    }

    /**
     * Whitespace is where a quote and its document differ without disagreeing: a pasted
     * advert carries the line breaks of wherever it was copied from.
     */
    private static String normalised(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    private static List<String> tags(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (JsonNode item : node) {
            String tag = text(item, MAX_TAG);
            if (tag != null && !tags.contains(tag)) {
                tags.add(tag);
            }
            if (tags.size() == MAX_TAGS) {
                break;
            }
        }
        return tags;
    }

    /**
     * A textual value, cut to its bound. {@code null}, a JSON null and the string "null" all
     * mean the document says nothing — the last of those because a model told to answer null
     * writes it as a word often enough to matter.
     */
    private static String text(JsonNode node, int max) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText().strip();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * What the model read, and which of the fields it filled.
     *
     * <p>The second half is not decoration: an upload is reviewed before it becomes an
     * offer, and a reviewer who cannot see which values were read by a model has to check
     * all of them equally.
     *
     * @param block     the fields, keyed as {@link OfferMapper} expects them.
     * @param fromModel the keys in {@code block} the model filled. The description is never
     *                  among them; it is the document itself.
     */
    public record Reading(Map<String, Object> block, Set<String> fromModel) {}
}
