/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest.connector;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.SourcesConfig.Connection;
import de.codeministry.leadgen.config.model.SourcesConfig.Selector;
import de.codeministry.leadgen.config.model.SourcesConfig.Source;
import de.codeministry.leadgen.ingest.IngestException;
import de.codeministry.leadgen.ingest.RawDocument;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.integration.mail.inbound.ImapMailReceiver;
import org.springframework.stereotype.Component;

/**
 * Reads newsletter mails from an IMAP mailbox, through Spring Integration's
 * {@code ImapMailReceiver}. Same extraction as the file source, only a different way of
 * getting at the HTML.
 *
 * <p><b>No message is ever flagged {@code \Seen}</b>, and that still takes two things:
 * {@code shouldMarkMessagesAsRead} is off <i>and</i> {@code mail.imap.peek} is on, because
 * fetching a body otherwise issues {@code FETCH BODY[]} and the server sets the flag
 * regardless. Measured against a real IMAP server: without the property, every mail a run
 * touches is marked read in the owner's mailbox.
 *
 * <p><b>Three guarantees were given up to get here, deliberately, and they are worth
 * naming.</b> The receiver tracks what it has seen with a <i>user flag</i> written into the
 * mailbox, not with a UID watermark kept on our side. So:
 *
 * <ul>
 *   <li>the tool no longer leaves the owner's mailbox untouched — it writes one flag per
 *       message it fetches, and on a server without user-flag support
 *       ({@code flaggedAsFallback} is off) it has no marker at all;
 *   <li>"a message the selector skipped is not progress" is gone. The receiver flags what
 *       its <i>search</i> returned, before this class post-filters on sender, subject and
 *       age, so widening a subject filter no longer makes the mails behind it reachable
 *       again;
 *   <li>the {@code UIDVALIDITY} reset is gone. A recreated folder has no equivalent in
 *       flags, and the receiver simply starts again with an unflagged mailbox.
 * </ul>
 *
 * <p>What is gained is that the protocol is the library's problem rather than ours,
 * including the {@code getMessagesByUID(start, LASTUID)} range lie the previous
 * implementation had to work around by hand.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImapSourceConnector implements SourceConnector {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * The flag the receiver writes to remember a message. Named after the tool rather than
     * left at the library default, so somebody looking at their mailbox can tell what put it
     * there.
     */
    private static final String USER_FLAG = "leadgen";

    private final ConfigRegistry config;

    /**
     * The application's own factory, handed to the receiver.
     *
     * <p>Not a throwaway one: `ImapMailReceiver` looks up `integrationEvaluationContext` on
     * init, so anything less than the real context fails with "No such bean" — which reads
     * like a wiring mistake and is actually a missing `@EnableIntegration`.
     */
    private final BeanFactory beans;

    @Override
    public String type() {
        return "imap";
    }

    @Override
    public List<RawDocument> read(Source source, long sourceId) {
        String preferred = source.extraction().preferPartOrHtml();
        Selector selector = selectorOf(source);
        ImapMailReceiver receiver = receiver(source, selector);
        try {
            Object[] received = receiver.receive();
            List<RawDocument> documents = new ArrayList<>();
            for (Object candidate : received) {
                Message message = mailMessageOf(candidate);
                if (message == null) {
                    continue;
                }
                if (!matches(message, selector)) {
                    continue;
                }
                documents.add(new RawDocument(
                        identityOf(message),
                        message.getSubject(),
                        partOf(message, preferred),
                        message.getReceivedDate() == null
                                ? Instant.now()
                                : message.getReceivedDate().toInstant()));
            }
            log.info(
                    "Source '{}': {} of {} messages the receiver handed over match the selector",
                    source.id(),
                    documents.size(),
                    received.length);
            return documents;
        } catch (MessagingException | IOException e) {
            throw new IngestException("source '%s' cannot read its mailbox".formatted(source.id()), e);
        } finally {
            receiver.destroy();
        }
    }

    /**
     * Nothing to commit any more, and that is the trade this connector made.
     *
     * <p>It used to advance a UID watermark to the highest message <em>actually
     * processed</em>, after the write — never to the highest in the folder — so a subject
     * filter that turned out too narrow could be widened and the mails behind it were
     * reachable again. The receiver marks each message with a user flag at fetch time
     * instead, which is a per-message decision already made by the time this would run.
     *
     * <p>Kept as an empty override rather than removed from the interface: the file source
     * has nothing to commit either, and a connector that genuinely needs a cursor should
     * still be able to have one.
     */
    @Override
    public void commit(Source source, long sourceId, List<RawDocument> processed) {
        // Intentionally empty. See above.
    }

    /**
     * The receiver, configured for a mailbox somebody else also reads.
     *
     * <p>Four settings and every one of them is about not disturbing the owner:
     * {@code peek} so a fetch does not set {@code \Seen}, {@code shouldMarkMessagesAsRead}
     * off for the same reason, {@code shouldDeleteMessages} off because nothing here owns
     * that mail, and {@code flaggedAsFallback} off so a server without user flags is not
     * silently given a {@code \Flagged} instead — a star the owner would see on their
     * phone.
     *
     * <p>No {@code SearchTermStrategy} is supplied on purpose. The default one is what
     * expresses "not already taken" in terms of the flags above, and replacing it would mean
     * reimplementing that. The selector's sender, subject and age rules are applied here
     * afterwards instead, exactly as before.
     */
    private ImapMailReceiver receiver(Source source, Selector selector) {
        Connection connection = connection(source);
        String protocol = connection.ssl() ? "imaps" : "imap";
        ImapMailReceiver receiver = new ImapMailReceiver(url(connection, protocol, selector.folder(), source));

        Properties properties = new Properties();
        properties.put("mail.store.protocol", protocol);
        properties.put("mail." + protocol + ".connectiontimeout", String.valueOf(TIMEOUT.toMillis()));
        properties.put("mail." + protocol + ".timeout", String.valueOf(TIMEOUT.toMillis()));
        properties.put("mail." + protocol + ".peek", "true");
        receiver.setJavaMailProperties(properties);

        receiver.setShouldMarkMessagesAsRead(false);
        receiver.setShouldDeleteMessages(false);
        receiver.setFlaggedAsFallback(false);
        receiver.setUserFlag(USER_FLAG);
        // The whole message, not the headers: the body is the document.
        receiver.setSimpleContent(false);
        // The folder must outlive `receive()`. The receiver hands back messages whose content
        // is still fetched lazily from the server, and with the default it closes the folder
        // on the way out — so reading a body afterwards throws `FolderClosedException` for
        // every single message. Closed instead in the `finally` below, once the bodies are read.
        receiver.setAutoCloseFolder(false);
        receiver.setBeanFactory(beans);
        receiver.afterPropertiesSet();
        return receiver;
    }

    /**
     * {@code imaps://user:password@host:port/folder}, with both credentials percent-encoded.
     *
     * <p>A password containing an {@code @} or a {@code /} is ordinary and would otherwise
     * split the URL somewhere in the middle, producing a connection attempt against a host
     * nobody configured — with the password in the error message.
     */
    private static String url(Connection connection, String protocol, String folder, Source source) {
        if (folder == null || folder.isBlank()) {
            throw new IngestException(
                    "source '%s' names no selector.folder; an IMAP source has to say which folder to read"
                            .formatted(source.id()));
        }
        String port = connection.port() == null ? "" : ":" + connection.port();
        return "%s://%s:%s@%s%s/%s"
                .formatted(
                        protocol,
                        encode(connection.username()),
                        encode(connection.password()),
                        connection.host(),
                        port,
                        folder.startsWith("/") ? folder.substring(1) : folder);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static Selector selectorOf(Source source) {
        if (source.selector() == null) {
            throw new IngestException(
                    "source '%s' declares no selector; an IMAP source has to say what to read".formatted(source.id()));
        }
        return source.selector();
    }

    /**
     * The mail message, whichever shape the receiver handed it over in.
     *
     * <p>Two shapes, and the difference is not documented anywhere near the setter that
     * causes it: with {@code autoCloseFolder} on, {@code receive()} returns
     * {@code jakarta.mail.Message} directly; with it off — which is what lets a body be read
     * after the call — it returns Spring {@code Message}s instead, carrying the mail as the
     * payload so the flow can close the folder later. Assuming the first shape silently
     * yields zero documents from a mailbox that is perfectly fine, which is exactly the
     * failure this source class is least able to notice.
     */
    private static Message mailMessageOf(Object candidate) {
        if (candidate instanceof Message message) {
            return message;
        }
        if (candidate instanceof org.springframework.messaging.Message<?> wrapper
                && wrapper.getPayload() instanceof Message message) {
            return message;
        }
        log.warn("The mail receiver handed over a {}, which is not a message", candidate.getClass());
        return null;
    }

    /**
     * The document's identity, and it is the message id rather than a UID.
     *
     * <p>The UID is gone with the cursor, and the message id is what the document carries
     * about itself — stable across a folder move, and meaningful in a log line. A mail
     * without one falls back to its subject and arrival time, which is the best available
     * answer and still stable for the same message.
     */
    private static String identityOf(Message message) throws MessagingException {
        String[] ids = message.getHeader("Message-ID");
        if (ids != null && ids.length > 0 && ids[0] != null && !ids[0].isBlank()) {
            return ids[0].trim();
        }
        return "%s@%s"
                .formatted(
                        message.getSubject(),
                        message.getReceivedDate() == null
                                ? "unknown"
                                : message.getReceivedDate().toInstant());
    }

    private Connection connection(Source source) {
        return config.snapshot().sources().connections().stream()
                .filter(candidate -> candidate.id().equals(source.connection()))
                .findFirst()
                // Unreachable through the loader, which rejects an undeclared connection.
                .orElseThrow(() -> new IngestException("source '%s' names connection '%s', which is not declared"
                        .formatted(source.id(), source.connection())));
    }

    /**
     * Whether the selector wants this message, and it says why when it does not.
     *
     * <p>The reason is logged rather than counted. "3 of 40 messages matched" is the kind of
     * number somebody stares at for an afternoon; "skipped, sender not in the list" answers
     * the question in one line, and a selector that is quietly too narrow is the failure this
     * source is most likely to have.
     */
    private static boolean matches(Message message, Selector selector) throws MessagingException {
        if (selector.sinceDays() != null
                && message.getReceivedDate() != null
                && message.getReceivedDate()
                        .toInstant()
                        .isBefore(Instant.now().minus(selector.sinceDays(), ChronoUnit.DAYS))) {
            log.debug("Skipping '{}': older than the configured window", message.getSubject());
            return false;
        }
        List<String> senders = sendersOf(message);
        if (contains(selector.excludeFrom(), senders)) {
            log.debug("Skipping '{}': sender {} is excluded", message.getSubject(), senders);
            return false;
        }
        // Dedicated mode: the folder holds nothing else, so there is nothing to filter on.
        if (selector.matchAll()) {
            return true;
        }
        if (selector.from() != null && !selector.from().isEmpty() && !contains(selector.from(), senders)) {
            log.debug("Skipping '{}': sender {} is not in {}", message.getSubject(), senders, selector.from());
            return false;
        }
        if (selector.subjectMatches() == null) {
            return true;
        }
        boolean subjectMatches = message.getSubject() != null
                && Pattern.compile(selector.subjectMatches())
                        .matcher(message.getSubject())
                        .find();
        if (!subjectMatches) {
            log.debug("Skipping '{}': the subject does not match {}", message.getSubject(), selector.subjectMatches());
        }
        return subjectMatches;
    }

    private static List<String> sendersOf(Message message) throws MessagingException {
        Address[] from = message.getFrom();
        if (from == null) {
            return List.of();
        }
        return java.util.Arrays.stream(from)
                .map(address ->
                        address instanceof InternetAddress internet ? internet.getAddress() : address.toString())
                .filter(java.util.Objects::nonNull)
                .map(address -> address.toLowerCase(Locale.ROOT))
                .toList();
    }

    private static boolean contains(List<String> configured, List<String> senders) {
        return configured != null
                && configured.stream()
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .anyMatch(senders::contains);
    }

    /**
     * The wanted alternative, not the first part. A newsletter is `multipart/alternative`
     * with the plain-text version first, and that version has none of the structure the
     * extraction rules address. Which one is wanted is `extraction.prefer_part`, defaulting
     * to html — searched from the back, because `multipart/alternative` orders its parts
     * least-preferred first.
     */
    private static String partOf(jakarta.mail.Part part, String preferred) throws MessagingException, IOException {
        if (part.isMimeType("text/" + preferred)) {
            return (String) part.getContent();
        }
        if (part.getContent() instanceof jakarta.mail.Multipart multipart) {
            for (int i = multipart.getCount() - 1; i >= 0; i--) {
                String html = partOf(multipart.getBodyPart(i), preferred);
                if (!html.isEmpty()) {
                    return html;
                }
            }
        }
        return "";
    }
}
