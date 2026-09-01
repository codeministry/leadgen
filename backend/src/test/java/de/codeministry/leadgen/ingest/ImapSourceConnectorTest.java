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
    static final GreenMailExtension MAIL =
            new GreenMailExtension(ServerSetupTest.IMAP).withPerMethodLifecycle(true);

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
    void neverFlagsAMessageAsSeen() {
        // The owner reads this mailbox on a phone. A run that marks mails read rewrites
        // what they see, and one that tracked progress by that flag would skip whatever
        // they opened first.
        deliver(NEWSLETTER, "3 neue Projekte sind da!");

        connector.read(source, sourceId);

        assertThat(flagsOfTheOnlyMessage().contains(Flags.Flag.SEEN)).isFalse();
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
    void advancesTheCursorOnlyOverMessagesItActuallyProcessed() {
        // A mail the selector skipped is not progress. A subject filter that turns out too
        // narrow is fixed by widening it, and the mails behind it have to still be there.
        deliver(NEWSLETTER, "3 neue Projekte sind da!");
        deliver(NEWSLETTER, "Ihre Rechnung");

        var documents = connector.read(source, sourceId);
        connector.commit(source, sourceId, documents);

        long lastUid = cursors.load(sourceId, "INBOX").lastUid();
        assertThat(lastUid).isEqualTo(1L);
    }

    @Test
    void readsFromTheStartAgainWhenUidValidityChanged() {
        deliver(NEWSLETTER, "3 neue Projekte sind da!");
        connector.commit(source, sourceId, connector.read(source, sourceId));

        // A recreated folder hands out the same UIDs for different messages. A cursor kept
        // across that would skip the whole folder, in silence.
        var stale = cursors.load(sourceId, "INBOX");
        cursors.save(sourceId, "INBOX", new de.codeministry.leadgen.ingest.store.IngestCursor(stale.uidValidity() + 1, 99));

        assertThat(connector.read(source, sourceId)).hasSize(1);
    }

    @Test
    void runsTheWholePipelineOverTheMailbox() {
        deliver(NEWSLETTER, "3 neue Projekte sind da!");

        var report = ingest.run();

        assertThat(report.extracted()).isEqualTo(3);
        assertThat(report.written()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM offer", Integer.class)).isEqualTo(3);
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
