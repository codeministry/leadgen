package de.codeministry.leadgen.manual;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import de.codeministry.leadgen.ingest.ExtractedOffer;
import de.codeministry.leadgen.ingest.extract.MarkdownExtractor;
import de.codeministry.leadgen.ingest.extract.OfferMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The review queue: what an upload does before it is allowed to become an offer.
 *
 * <p>No staging table. The file is the state, which means it can be read with `cat`, a
 * rejected upload is a file that was deleted rather than a row nobody will look at again,
 * and confirming is a move. The alternative would put half the pipeline's truth in a table
 * that the source knows nothing about.
 *
 * <p>Why review at all: a pasted ad has no guaranteed frontmatter and a key spelled
 * differently is read and then ignored, in silence. A bad extraction would otherwise reach
 * the shortlist, which is the one list that gets trusted instead of the mailbox.
 */
@Slf4j
@Service
public class ManualUploadService {

    /** Generous for an advert, small enough that nothing here is a place to store files. */
    public static final long MAX_BYTES = 512 * 1024L;

    private final ManualInbox inbox;
    private final MarkdownExtractor markdown;
    private final OfferMapper mapper;
    private final JdbcClient jdbc;
    private final JsonMapper yaml = JsonMapper.builder(
                    new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER))
            .build();

    ManualUploadService(
            ManualInbox inbox,
            MarkdownExtractor markdown,
            OfferMapper mapper,
            DataSource dataSource) {
        this.inbox = inbox;
        this.markdown = markdown;
        this.mapper = mapper;
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** Thrown when there is no enabled `manual-inbox` source to write into. */
    public static class NoInbox extends RuntimeException {
        public NoInbox() {
            super("no enabled 'manual-inbox' source is configured, so there is nowhere to put the document");
        }
    }

    /**
     * Writes an upload into `pending/`, where no source globs it.
     *
     * @return the document as the extraction reads it right now, so the review screen needs
     *     no second request to show what is about to enter.
     */
    public PendingDocument store(String rawName, byte[] content) {
        if (content.length == 0) {
            throw new ManualDocumentName.Rejected("the document is empty");
        }
        if (content.length > MAX_BYTES) {
            throw new ManualDocumentName.Rejected(
                    "the document is " + content.length + " bytes; the limit is " + MAX_BYTES);
        }
        Path pending = pendingDirectory();
        Path target = ManualDocumentName.resolve(pending, rawName);
        try {
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + target, e);
        }
        log.info("Manual upload {} stored for review", target.getFileName());
        return describe(target);
    }

    /** Everything waiting for review, newest last so the list reads as a queue. */
    public List<PendingDocument> pending() {
        Path directory = pendingDirectory();
        try (var files = Files.list(directory)) {
            List<PendingDocument> documents = new ArrayList<>();
            files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(ManualDocumentName.EXTENSION))
                    .sorted(Comparator.comparing(file -> file.getFileName().toString()))
                    .forEach(file -> documents.add(describe(file)));
            return documents;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + directory, e);
        }
    }

    public Optional<PendingDocument> find(String name) {
        Path file = ManualDocumentName.resolve(pendingDirectory(), name);
        return Files.isRegularFile(file) ? Optional.of(describe(file)) : Optional.empty();
    }

    /**
     * Writes the corrected fields back into the file and moves it where the source reads.
     *
     * <p>The correction goes into the document rather than into a database, so what enters
     * the pipeline is exactly what was reviewed — and if the extraction is later changed,
     * re-reading the same file produces the same offer.
     */
    public PendingDocument confirm(String name, ManualOfferFields fields) {
        Path source = ManualDocumentName.resolve(pendingDirectory(), name);
        if (!Files.isRegularFile(source)) {
            throw new ManualDocumentName.Rejected("no document named '" + name + "' is waiting for review");
        }
        Path target = ManualDocumentName.resolve(inboxDirectory(), name);
        try {
            Files.writeString(source, document(fields), StandardCharsets.UTF_8);
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot confirm " + source, e);
        }
        log.info("Manual upload {} confirmed; the next run will read it", target.getFileName());
        return describe(target);
    }

    /** A rejected upload leaves nothing behind. */
    public boolean reject(String name) {
        Path file = ManualDocumentName.resolve(pendingDirectory(), name);
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete " + file, e);
        }
    }

    /** The document as YAML frontmatter plus the description as the body. */
    String document(ManualOfferFields fields) {
        Map<String, Object> front = new LinkedHashMap<>();
        put(front, OfferMapper.TITLE, fields.title());
        put(front, OfferMapper.URL, fields.url());
        put(front, OfferMapper.LOCATION, fields.location());
        put(front, OfferMapper.PORTAL, fields.portal());
        put(front, OfferMapper.AGENCY, fields.agency());
        put(front, OfferMapper.PUBLISHED, fields.published());
        if (fields.tags() != null && !fields.tags().isEmpty()) {
            front.put(OfferMapper.TAGS, fields.tags());
        }
        try {
            String frontmatter = yaml.writeValueAsString(front);
            String body = fields.description() == null ? "" : fields.description().strip();
            return "---\n" + frontmatter + "---\n\n" + body + "\n";
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the frontmatter", e);
        }
    }

    private static void put(Map<String, Object> front, String key, String value) {
        if (value != null && !value.isBlank()) {
            front.put(key, value.strip());
        }
    }

    private PendingDocument describe(Path file) {
        String text = read(file);
        var extraction = inbox.source().orElseThrow(NoInbox::new).extraction();
        ExtractedOffer offer = markdown.extract(text, extraction).stream()
                // No arrival date: a file dropped in by hand did not come in the post, and
                // the file's own timestamp would be the upload's, dressed up as one.
                .map(block -> mapper.map(block, extraction, null))
                .findFirst()
                .orElse(null);

        Long duplicateId = null;
        String duplicateTitle = null;
        if (offer != null && offer.fingerprint() != null && !offer.fingerprint().isBlank()) {
            var existing = duplicateOf(offer.fingerprint());
            if (existing.isPresent()) {
                duplicateId = existing.get().id();
                duplicateTitle = existing.get().title();
            }
        }
        return new PendingDocument(
                file.getFileName().toString(), size(file), modified(file), text, offer, duplicateId, duplicateTitle);
    }

    /**
     * The same fingerprint deduplication uses, asked before the confirm rather than after.
     * Adding something already in the pipeline should cost nothing and say so, instead of
     * producing a second row that the next dedupe pass quietly collapses.
     */
    private Optional<Existing> duplicateOf(String fingerprint) {
        return jdbc.sql("SELECT id, title FROM offer WHERE fingerprint = ? ORDER BY ingested_at LIMIT 1")
                .param(fingerprint)
                .query((rs, row) -> new Existing(rs.getLong("id"), rs.getString("title")))
                .optional();
    }

    private record Existing(long id, String title) {}

    private Path pendingDirectory() {
        return inbox.pending().orElseThrow(NoInbox::new);
    }

    private Path inboxDirectory() {
        return inbox.inbox().orElseThrow(NoInbox::new);
    }

    /** The upload is text by contract, so a file that is not UTF-8 is a rejected file. */
    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static java.time.Instant modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return java.time.Instant.now();
        }
    }

}
