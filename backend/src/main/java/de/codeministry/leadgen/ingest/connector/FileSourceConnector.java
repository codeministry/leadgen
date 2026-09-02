/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest.connector;

import de.codeministry.leadgen.config.ConfigProperties;
import de.codeministry.leadgen.config.Directories;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Reads documents from a directory. The source that needs no mailbox, no credentials
 * and no network — which is what makes the extraction reproducible and the acceptance
 * test possible at all.
 */
@Slf4j
@Component
public class FileSourceConnector implements SourceConnector {

    private static final Session SESSION = Session.getInstance(new Properties());

    private final Path configDirectory;

    public FileSourceConnector(ConfigProperties properties) {
        this.configDirectory = properties.configDirectory();
    }

    @Override
    public String type() {
        return "file";
    }

    @Override
    public List<RawDocument> read(Source source, long sourceId) {
        String preferred = source.extraction().preferPartOrHtml();
        Path directory = directoryFor(source);
        if (directory == null) {
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
                    .forEach(file -> documents.add(readOne(file, preferred)));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + directory, e);
        }
        return documents;
    }

    /**
     * Where this source reads from.
     *
     * <p>A relative path names a place inside the configuration directory, exactly as a
     * template path does. Resolved against the working directory it would point at
     * `backend/…` under `bootRun` and at the repository root in an IDE, and an empty
     * directory looks exactly like a quiet day on the market.
     *
     * <p>An existing configuration that already named a working-directory path still
     * works, because breaking one over a style is not worth it — <b>and the fallback logs
     * a warning naming both paths</b>, since it can resolve outside the directory the
     * process was pointed at and looks entirely normal doing it.
     *
     * @return null when neither reading is a directory, which is not fatal: one source
     *     with nothing behind it must not stop the sources after it.
     */
    private Path directoryFor(Source source) {
        Path preferred = Directories.under(configDirectory, source.path());
        if (Files.isDirectory(preferred)) {
            return preferred;
        }
        Path fallback = Directories.resolve(source.path());
        if (Files.isDirectory(fallback)) {
            log.warn(
                    "Source '{}' reads from {}, not from {} — the path is relative to the working directory"
                            + " rather than to the configuration directory",
                    source.id(),
                    fallback,
                    preferred);
            return fallback;
        }
        log.warn("Source '{}' points at {}, which is not a directory", source.id(), preferred);
        return null;
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

    private RawDocument readOne(Path file, String preferred) {
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
            return new RawDocument(name, message.getSubject(), partOf(message, preferred), received(message, file));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        } catch (MessagingException e) {
            log.warn("Cannot parse {} as a mail, skipping its content", name, e);
            return new RawDocument(name, name, "", lastModified(file));
        }
    }

    /**
     * The wanted alternative, not the first part. A newsletter is `multipart/alternative`
     * with the plain-text version first; taking part zero yields text that has none of the
     * structure the extraction rules address. Which one is wanted is
     * `extraction.prefer_part`, defaulting to html — searched from the back, because
     * `multipart/alternative` orders its parts least-preferred first.
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

    private static Instant received(MimeMessage message, Path file) throws MessagingException {
        return message.getSentDate() == null
                ? lastModified(file)
                : message.getSentDate().toInstant();
    }

    private static Instant lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return Instant.now();
        }
    }
}
