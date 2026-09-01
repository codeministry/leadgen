package de.codeministry.leadgen.ingest.connector;

import de.codeministry.leadgen.config.model.SourcesConfig.Source;
import de.codeministry.leadgen.ingest.RawDocument;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads documents from a directory. The source that needs no mailbox, no credentials
 * and no network — which is what makes the extraction reproducible and the acceptance
 * test possible at all.
 */
@Component
public class FileSourceConnector implements SourceConnector {

    private static final Logger log = LoggerFactory.getLogger(FileSourceConnector.class);
    private static final Session SESSION = Session.getInstance(new Properties());

    @Override
    public String type() {
        return "file";
    }

    @Override
    public List<RawDocument> read(Source source) {
        Path directory = Path.of(source.path());
        if (!Files.isDirectory(directory)) {
            log.warn("Source '{}' points at {}, which is not a directory", source.id(), directory.toAbsolutePath());
            return List.of();
        }

        List<String> suffixes = suffixes(source.glob());
        List<RawDocument> documents = new ArrayList<>();

        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(file -> matches(file, suffixes))
                    // Sorted so a run is reproducible: the acceptance test compares
                    // per-document counts, and directory order is not an ordering.
                    .sorted(Comparator.comparing(file -> file.getFileName().toString()))
                    .forEach(file -> documents.add(readOne(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + directory, e);
        }
        return documents;
    }

    private static List<String> suffixes(String glob) {
        if (glob == null || glob.isBlank()) {
            return List.of();
        }
        return Arrays.stream(glob.split(","))
                .map(String::trim)
                .filter(pattern -> !pattern.isEmpty())
                .map(pattern -> pattern.startsWith("*") ? pattern.substring(1) : pattern)
                .toList();
    }

    private static boolean matches(Path file, List<String> suffixes) {
        String name = file.getFileName().toString();
        return suffixes.isEmpty() || suffixes.stream().anyMatch(name::endsWith);
    }

    private RawDocument readOne(Path file) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".eml")) {
            try {
                return new RawDocument(name, name, Files.readString(file, StandardCharsets.UTF_8), lastModified(file));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + file, e);
            }
        }

        try (InputStream in = Files.newInputStream(file)) {
            MimeMessage message = new MimeMessage(SESSION, in);
            return new RawDocument(name, message.getSubject(), htmlPartOf(message), received(message, file));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        } catch (MessagingException e) {
            log.warn("Cannot parse {} as a mail, skipping its content", name, e);
            return new RawDocument(name, name, "", lastModified(file));
        }
    }

    /**
     * The HTML alternative, not the first part. A newsletter is `multipart/alternative`
     * with the plain-text version first; taking part zero yields text that has none of
     * the structure the extraction rules address.
     */
    private static String htmlPartOf(jakarta.mail.Part part) throws MessagingException, IOException {
        if (part.isMimeType("text/html")) {
            return (String) part.getContent();
        }
        if (part.getContent() instanceof jakarta.mail.Multipart multipart) {
            for (int i = multipart.getCount() - 1; i >= 0; i--) {
                String html = htmlPartOf(multipart.getBodyPart(i));
                if (!html.isEmpty()) {
                    return html;
                }
            }
        }
        return "";
    }

    private static Instant received(MimeMessage message, Path file) throws MessagingException {
        return message.getSentDate() == null ? lastModified(file) : message.getSentDate().toInstant();
    }

    private static Instant lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return Instant.now();
        }
    }
}
