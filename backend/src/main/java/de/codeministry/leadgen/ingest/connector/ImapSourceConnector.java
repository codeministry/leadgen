package de.codeministry.leadgen.ingest.connector;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.SourcesConfig.Connection;
import de.codeministry.leadgen.config.model.SourcesConfig.Selector;
import de.codeministry.leadgen.config.model.SourcesConfig.Source;
import de.codeministry.leadgen.ingest.IngestException;
import de.codeministry.leadgen.ingest.RawDocument;
import de.codeministry.leadgen.ingest.store.IngestCursor;
import de.codeministry.leadgen.ingest.store.IngestCursorStore;
import jakarta.mail.Address;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import java.io.IOException;
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
import org.springframework.stereotype.Component;

/**
 * Reads newsletter mails from an IMAP mailbox. Same extraction as the file source, only a
 * different way of getting at the HTML.
 *
 * <p>Two things are load-bearing and both fail silently when they are wrong.
 *
 * <p><b>No message is ever flagged {@code \Seen}.</b> That takes two things, and the
 * obvious one alone is not enough: the folder is opened read-only <i>and</i>
 * {@code mail.imap.peek} is on, because fetching a body otherwise issues
 * {@code FETCH BODY[]} and the server sets the flag regardless of how the folder was
 * opened.
 * The same mailbox is read on a phone; a run that marks mails read would rewrite what the
 * owner sees, and one that tracked its own progress by that flag would skip everything the
 * owner opened first. Progress is therefore {@code UIDVALIDITY}/{@code UID} and nothing
 * else — `state: uid` is the only accepted value.
 *
 * <p><b>{@code getMessagesByUID(start, LASTUID)} lies about its range.</b> It returns the
 * message with the highest UID even when that UID is below {@code start}, so a mailbox
 * with nothing new hands back its newest mail as if it were unread. Without the filter
 * below, every run would re-extract the last mail forever — which the upsert would hide.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImapSourceConnector implements SourceConnector {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final ConfigRegistry config;
    private final IngestCursorStore cursors;

    @Override
    public String type() {
        return "imap";
    }

    @Override
    public List<RawDocument> read(Source source, long sourceId) {
        String preferred = source.extraction().preferPartOrHtml();
        return withFolder(source, folder -> {
            // `UIDFolder` is jakarta.mail's own interface, not the provider's class: the
            // whole UID protocol lives there, so nothing here depends on Angus.
            UIDFolder uids = (UIDFolder) folder;
            Selector selector = source.selector();
            long uidValidity = uids.getUIDValidity();
            IngestCursor cursor = cursors.load(sourceId, selector.folder()).validFor(uidValidity);

            if (cursor.lastUid() == 0 && cursor.uidValidity() != uidValidity) {
                log.info("UIDVALIDITY of {} changed; the folder was recreated and is read from the start",
                        selector.folder());
            }

            Message[] candidates = uids.getMessagesByUID(cursor.lastUid() + 1, UIDFolder.LASTUID);
            List<RawDocument> documents = new ArrayList<>();

            for (Message message : candidates) {
                long uid = uids.getUID(message);
                if (uid <= cursor.lastUid()) {
                    continue; // the documented lie about the range
                }
                if (!matches(message, selector)) {
                    continue;
                }
                documents.add(new RawDocument(
                        "%d:%d".formatted(uidValidity, uid),
                        message.getSubject(),
                        partOf(message, preferred),
                        message.getReceivedDate() == null ? Instant.now() : message.getReceivedDate().toInstant()));
            }
            log.info("Source '{}': {} of {} messages above UID {} match the selector",
                    source.id(), documents.size(), candidates.length, cursor.lastUid());
            return documents;
        });
    }

    /**
     * Advances the cursor to the highest UID actually processed — never to the highest UID
     * in the folder. A message the selector skipped is not progress: a subject filter that
     * turns out to be too narrow is fixed by widening it, and the mails behind it have to
     * still be reachable.
     */
    @Override
    public void commit(Source source, long sourceId, List<RawDocument> processed) {
        if (processed.isEmpty()) {
            return;
        }
        long uidValidity = Long.parseLong(processed.getFirst().id().split(":")[0]);
        long highest = processed.stream()
                .mapToLong(document -> Long.parseLong(document.id().split(":")[1]))
                .max()
                .orElseThrow();

        cursors.save(sourceId, source.selector().folder(), new IngestCursor(uidValidity, highest));
        log.info("Source '{}': cursor advanced to {}:{}", source.id(), uidValidity, highest);
    }

    private <T> T withFolder(Source source, ImapAction<T> action) {
        Connection connection = connection(source);
        Properties properties = new Properties();
        String protocol = connection.ssl() ? "imaps" : "imap";
        properties.put("mail.store.protocol", protocol);
        properties.put("mail." + protocol + ".connectiontimeout", String.valueOf(TIMEOUT.toMillis()));
        properties.put("mail." + protocol + ".timeout", String.valueOf(TIMEOUT.toMillis()));
        // Opening the folder read-only is NOT enough. Fetching a body issues FETCH BODY[],
        // which sets \Seen server-side; only BODY.PEEK[] does not, and JavaMail uses it
        // solely when this is on. Measured against a real IMAP server: without it every
        // mail the run touches is marked read in the owner's mailbox.
        properties.put("mail." + protocol + ".peek", "true");

        try (Store store = Session.getInstance(properties).getStore(protocol)) {
            store.connect(
                    connection.host(),
                    connection.port() == null ? -1 : connection.port(),
                    connection.username(),
                    connection.password());

            Folder folder = open(store, source);
            try {
                return action.apply(folder);
            } finally {
                folder.close(false);
            }
        } catch (MessagingException | IOException e) {
            throw new IngestException("source '%s' cannot read its mailbox".formatted(source.id()), e);
        }
    }

    /**
     * Opens the configured folder, and says something useful when it is not there.
     *
     * <p>The folder is a server-side path, and its separator is the server's to choose:
     * "/" on Dovecot, "." on Courier and older Cyrus. A leading separator is never part of
     * the name, so `/Jobs` — which is how a mail client displays it — is normalised to
     * `Jobs` rather than failing. A subfolder of the inbox is usually `INBOX/Jobs`, which
     * is why the failure lists what the mailbox actually holds instead of only saying no:
     * "folder not found" without the alternatives is a message that ends the working day.
     */
    private static Folder open(Store store, Source source) throws MessagingException {
        String configured = source.selector().folder();
        if (configured == null || configured.isBlank()) {
            throw new IngestException(
                    "source '%s' names no selector.folder; an IMAP source has to say which folder to read"
                            .formatted(source.id()));
        }

        char separator = store.getDefaultFolder().getSeparator();
        String name = configured;
        while (!name.isEmpty() && (name.charAt(0) == separator || name.charAt(0) == '/')) {
            name = name.substring(1);
        }
        name = name.replace('/', separator);
        if (!name.equals(configured)) {
            log.info("Source '{}': folder '{}' read as '{}' (this server separates with '{}')",
                    source.id(), configured, name, separator);
        }

        Folder folder = store.getFolder(name);
        if (!folder.exists()) {
            throw new IngestException("source '%s': the mailbox has no folder '%s'. It has: %s"
                    .formatted(source.id(), configured, available(store, separator)));
        }
        // READ_ONLY, so nothing is ever flagged \Seen. The owner reads this mailbox too.
        folder.open(Folder.READ_ONLY);
        return folder;
    }

    /** Every folder the account can subscribe to, so a typo answers itself. */
    private static String available(Store store, char separator) {
        try {
            return java.util.Arrays.stream(store.getDefaultFolder().list("*"))
                    .map(Folder::getFullName)
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(", "));
        } catch (MessagingException e) {
            return "<the folder list could not be read: " + e.getMessage() + ">";
        }
    }

    private Connection connection(Source source) {
        return config.snapshot().sources().connections().stream()
                .filter(candidate -> candidate.id().equals(source.connection()))
                .findFirst()
                // Unreachable through the loader, which rejects an undeclared connection.
                .orElseThrow(() -> new IngestException(
                        "source '%s' names connection '%s', which is not declared"
                                .formatted(source.id(), source.connection())));
    }

    private static boolean matches(Message message, Selector selector) throws MessagingException {
        if (selector.sinceDays() != null
                && message.getReceivedDate() != null
                && message.getReceivedDate()
                        .toInstant()
                        .isBefore(Instant.now().minus(selector.sinceDays(), ChronoUnit.DAYS))) {
            return false;
        }
        List<String> senders = sendersOf(message);
        if (contains(selector.excludeFrom(), senders)) {
            return false;
        }
        // Dedicated mode: the folder holds nothing else, so there is nothing to filter on.
        if (selector.matchAll()) {
            return true;
        }
        if (selector.from() != null && !selector.from().isEmpty() && !contains(selector.from(), senders)) {
            return false;
        }
        return selector.subjectMatches() == null
                || (message.getSubject() != null
                        && Pattern.compile(selector.subjectMatches()).matcher(message.getSubject()).find());
    }

    private static List<String> sendersOf(Message message) throws MessagingException {
        Address[] from = message.getFrom();
        if (from == null) {
            return List.of();
        }
        return java.util.Arrays.stream(from)
                .map(address -> address instanceof InternetAddress internet ? internet.getAddress() : address.toString())
                .filter(java.util.Objects::nonNull)
                .map(address -> address.toLowerCase(Locale.ROOT))
                .toList();
    }

    private static boolean contains(List<String> configured, List<String> senders) {
        return configured != null
                && configured.stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(senders::contains);
    }

    /**
     * The wanted alternative, not the first part. A newsletter is `multipart/alternative`
     * with the plain-text version first, and that version has none of the structure the
     * extraction rules address. Which one is wanted is `extraction.prefer_part`, defaulting
     * to html — searched from the back, because `multipart/alternative` orders its parts
     * least-preferred first.
     */
    private static String partOf(jakarta.mail.Part part, String preferred)
            throws MessagingException, IOException {
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

    @FunctionalInterface
    private interface ImapAction<T> {
        T apply(Folder folder) throws MessagingException, IOException;
    }
}
