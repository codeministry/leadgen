/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.SourcesConfig.Source;
import de.codeministry.leadgen.ingest.connector.ImapSourceConnector;
import de.codeministry.leadgen.ingest.store.IngestCursorStore;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Session;
import jakarta.mail.Store;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
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
 * A real IMAP server in-process. The UID semantics this connector rests on cannot be
 * faked convincingly, and they are exactly where the mistakes are.
 */
@SpringBootTest
@Testcontainers
class ImapSourceConnectorTest {

    private static final String USER = "someone@example.com";
    private static final String PASSWORD = "secret";
    private static final String NEWSLETTER = "newsletter@example.com";

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(ServerSetupTest.IMAP).withPerMethodLifecycle(true);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private ImapSourceConnector connector;

    @Autowired
    private ConfigRegistry config;

    @Autowired
    private IngestCursorStore cursors;

    @Autowired
    private IngestService ingest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private de.codeministry.leadgen.ingest.store.OfferStore offers;

    private Source source;
    private com.icegreen.greenmail.user.GreenMailUser mailbox;
    private long sourceId;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> imapConfig().toString());
    }

    @BeforeEach
    void setUp() {
        mailbox = MAIL.setUser(USER, USER, PASSWORD);
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM ingest_cursor");
        // The cursor has a foreign key on `source`, so the row has to exist. Reusing a
        // literal id here made the outcome depend on which test had run before.
        sourceId = offers.sourceId("imap-newsletter", "imap");
        source = config.snapshot().sources().sources().stream()
                .filter(s -> s.id().equals("imap-newsletter"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void readsOnlyTheMessagesTheSelectorMatches() {
        deliver(NEWSLETTER, "3 neue Projekte sind da!");
        deliver("someone-else@example.com", "3 neue Projekte sind da!");
        deliver(NEWSLETTER, "Ihre Rechnung");

        assertThat(connector.read(source, sourceId)).singleElement().satisfies(document -> {
            assertThat(document.subject()).isEqualTo("3 neue Projekte sind da!");
            assertThat(document.html()).contains("job-card");
        });
    }

    @Test
    void takesTheHtmlAlternativeAndNotThePlainTextOne() {
        deliver(NEWSLETTER, "3 neue Projekte sind da!");

        var document = connector.read(source, sourceId).getFirst();

        assertThat(document.html()).contains("<h3 class=\"job-title\">").doesNotContain("plain-text alternative");
    }

    @Test
    void marksAMessageWithItsOwnFlagAndTouchesNoFlagTheOwnerSees() {
        // This is the trade Spring Integration's receiver comes with, written down rather
        // than discovered later.
        //
        // What still holds, and is the part that matters on a phone: no \\Seen, so nothing
        // the owner has not read appears read; no \\Flagged, because `flaggedAsFallback` is
        // off, so no star appears beside a mail; and no \\Deleted.
        //
        // What changed: progress used to be a UID watermark kept on our side, and the
        // mailbox was never written to at all. The receiver instead marks each message it
        // hands over with a user flag. Most clients do not show it, but it is a write to
        // somebody else's mailbox and the previous implementation did not make one.
        deliver(NEWSLETTER, "3 neue Projekte sind da!");

        connector.read(source, sourceId);

        Flags flags = flagsOfTheOnlyMessage();
        assertThat(flags.contains(Flags.Flag.SEEN)).isFalse();
        assertThat(flags.contains(Flags.Flag.FLAGGED)).isFalse();
        assertThat(flags.contains(Flags.Flag.ANSWERED)).isFalse();
        assertThat(flags.contains(Flags.Flag.DELETED)).isFalse();
        assertThat(flags.getUserFlags()).containsExactly("leadgen");
    }

    @Test
    void handsOverAMessageTheOwnerHasAlreadyRead() {
        // The mailbox is somebody else's and they read it on a phone, so \\Seen says nothing
        // about whether this tool has taken a message. Spring Integration's default search
        // strategy disagrees: it excludes every \\Seen message, which in a folder the owner
        // actually reads is all of them. The run then reports zero documents and no error,
        // and fewer offers looks exactly like a quiet day on the market.
        //
        // Measured against a real mailbox before the strategy was replaced: 165 mails in the
        // folder, 165 matching NOT KEYWORD leadgen, 0 matching the default term.
        //
        // Every other test here delivers a fresh mail, which is unseen, so this is the one
        // that pins it.
        deliver(NEWSLETTER, "3 neue Projekte sind da!");
        markTheOnlyMessageAsRead();

        assertThat(connector.read(source, sourceId))
                .singleElement()
                .satisfies(document -> assertThat(document.subject()).isEqualTo("3 neue Projekte sind da!"));
    }

    @Test
    void returnsNothingWhenTheCursorIsAlreadyPastEverything() {
        // getMessagesByUID(start, LASTUID) hands back the highest-UID message even when
        // its UID is below start. Unfiltered, every run would re-extract the last mail
        // forever, and the upsert would hide it.
        deliver(NEWSLETTER, "3 neue Projekte sind da!");
        var first = connector.read(source, sourceId);
        connector.commit(source, sourceId, first);

        assertThat(connector.read(source, sourceId)).isEmpty();
    }

    @Test
    void picksUpWhereItLeftOff() {
        deliver(NEWSLETTER, "3 neue Projekte sind da!");
        connector.commit(source, sourceId, connector.read(source, sourceId));

        deliver(NEWSLETTER, "7 neue Projekte sind da!");

        assertThat(connector.read(source, sourceId))
                .singleElement()
                .satisfies(document -> assertThat(document.subject()).isEqualTo("7 neue Projekte sind da!"));
    }

    @Test
    void handsOverAMessageOnceAndThenNotAgain() {
        // What replaced the cursor. The receiver flags what its own search returned, so a
        // second run sees nothing new — which is the useful half of the old guarantee.
        //
        // The half that is gone: the flag is written to everything the search returned,
        // before the selector below rejects the invoice. Widening a subject filter therefore
        // no longer makes the mails behind it reachable again, and the old test that pinned
        // that behaviour is deleted rather than weakened.
        deliver(NEWSLETTER, "3 neue Projekte sind da!");
        deliver(NEWSLETTER, "Ihre Rechnung");

        var first = connector.read(source, sourceId);
        connector.commit(source, sourceId, first);
        var second = connector.read(source, sourceId);

        assertThat(first).hasSize(1);
        assertThat(second).isEmpty();
    }

    @Test
    void runsTheWholePipelineOverTheMailbox() {
        deliver(NEWSLETTER, "3 neue Projekte sind da!");

        var report = ingest.run();

        assertThat(report.extracted()).isEqualTo(3);
        assertThat(report.written()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM offer", Integer.class))
                .isEqualTo(3);
        // A second pass has nothing left to read, so it writes nothing at all.
        assertThat(ingest.run().extracted()).isZero();
    }

    /**
     * Delivers the fixture itself, sender and subject overridden. Built from the raw `.eml`
     * rather than assembled in code, so the message the connector sees has the same MIME
     * structure as a real newsletter — which is the thing the HTML-part lookup depends on.
     */
    private void deliver(String from, String subject) {
        try (var in = Files.newInputStream(Path.of("src/test/resources/ingest/mails/sample.eml"))) {
            var message = new jakarta.mail.internet.MimeMessage(Session.getInstance(new Properties()), in);
            message.setFrom(new jakarta.mail.internet.InternetAddress(from));
            message.setSubject(subject);
            message.saveChanges();
            mailbox.deliver(message);
        } catch (IOException | jakarta.mail.MessagingException e) {
            throw new IllegalStateException("cannot deliver the fixture", e);
        }
    }

    /** What the owner's mail client does the moment they open the newsletter. */
    private void markTheOnlyMessageAsRead() {
        Properties properties = new Properties();
        properties.put("mail.store.protocol", "imap");
        try (Store store = Session.getInstance(properties).getStore("imap")) {
            store.connect("127.0.0.1", ServerSetupTest.IMAP.getPort(), USER, PASSWORD);
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);
            try {
                inbox.getMessage(1).setFlag(Flags.Flag.SEEN, true);
            } finally {
                inbox.close(false);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Flags flagsOfTheOnlyMessage() {
        Properties properties = new Properties();
        properties.put("mail.store.protocol", "imap");
        try (Store store = Session.getInstance(properties).getStore("imap")) {
            store.connect("127.0.0.1", ServerSetupTest.IMAP.getPort(), USER, PASSWORD);
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            try {
                return inbox.getMessage(1).getFlags();
            } finally {
                inbox.close(false);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The shipped example plus one IMAP source pointed at the in-process server. */
    private static Path imapConfig() {
        try {
            Path dir = Files.createTempDirectory("leadgen-imap");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);

            Path sources = dir.resolve("sources.yaml");
            String extraction = extractionBlockOfSampleNewsletter(Files.readString(sources));
            Files.writeString(
                    sources,
                    """
                    version: 1
                    connections:
                      - id: local-imap
                        type: imap
                        host: 127.0.0.1
                        port: %d
                        ssl: false
                        username: %s
                        password: %s
                    sources:
                      - id: imap-newsletter
                        enabled: true
                        type: imap
                        connection: local-imap
                        selector:
                          folder: INBOX
                          from: ["%s"]
                          subject_matches: "^\\\\d+ neue Projekte sind da!$"
                          mark_seen: false
                          state: uid
                    %s"""
                            .formatted(ServerSetupTest.IMAP.getPort(), USER, PASSWORD, NEWSLETTER, extraction));
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The extraction rules are taken from the shipped example rather than repeated here.
     * Two copies of a selector table drift, and the copy in a test drifts unnoticed.
     */
    private static String extractionBlockOfSampleNewsletter(String yaml) {
        var lines = yaml.lines().toList();
        int source = lines.indexOf("  - id: sample-newsletter");
        int start = -1;
        for (int i = source; i < lines.size(); i++) {
            if (lines.get(i).equals("    extraction:")) {
                start = i;
                break;
            }
        }
        if (source < 0 || start < 0) {
            throw new IllegalStateException("resources/leadgen/sources.yaml has no sample-newsletter extraction block");
        }
        int end = start + 1;
        while (end < lines.size() && (lines.get(end).isBlank() || lines.get(end).startsWith("      "))) {
            end++;
        }
        return String.join("\n", lines.subList(start, end)) + "\n";
    }
}
