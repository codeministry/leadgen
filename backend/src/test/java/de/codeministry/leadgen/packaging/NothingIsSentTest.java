/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.packaging;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.config.ConfigFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ISC-52, the anti-criterion that outranks every other in this feature: the tool has no
 * send path at all, and the configuration models no transport, recipient or channel
 * either.
 *
 * <p>It is enforced by reading the repository rather than by review, because this is the
 * kind of thing that arrives one convenient afternoon. Packaging is where it would arrive:
 * the folder is finished, the address is right there in `meta.json`, and a send button is
 * fifteen lines away.
 *
 * <p>The mail dependency stays, and legitimately — `spring-boot-starter-mail` is how the
 * IMAP connector <em>reads</em> a mailbox. Reading is `Store` and `Folder`; sending is
 * `Transport`, `JavaMailSender` and `MimeMessage`, and those are what this looks for.
 */
class NothingIsSentTest {

    /**
     * Ways to actually send something, as opposed to ways to talk about sending.
     *
     * <p>{@code new MimeMessage} is deliberately absent: that is how an `.eml` file is
     * <em>parsed</em>, and `FileSourceConnector` does exactly that. The send path is
     * `Transport.send` and the Spring helpers around it.
     */
    private static final List<Pattern> SEND_PATHS = List.of(
            Pattern.compile("\\bTransport\\s*\\.\\s*send"),
            Pattern.compile("\\bJavaMailSender\\b"),
            Pattern.compile("\\bMimeMessageHelper\\b"),
            Pattern.compile("\\bsetRecipients?\\s*\\("),
            Pattern.compile("mailto:"));

    /**
     * Configuration keys that would model an outbound channel.
     *
     * <p>{@code channel} is not among them, and that is the interesting exclusion:
     * `sources.yaml` uses it for where an offer <em>came from</em> — newsletter, portal,
     * direct. Inbound and outbound share the word, and a check that cannot tell them
     * apart is a check that gets switched off.
     */
    private static final List<Pattern> TRANSPORT_KEYS =
            List.of(Pattern.compile("(?m)^\\s*(transport|recipients?|smtp|webhook|mail_?to)\\s*:"));

    @Test
    void noSourceFileContainsASendPath() {
        var offenders = scan(ConfigFixtures.repositoryRoot().resolve("backend/src/main/java"), ".java", SEND_PATHS);
        assertThat(offenders)
                .as("a send path in the application is exactly what ISC-52 forbids")
                .isEmpty();
    }

    @Test
    void noShippedConfigurationModelsATransport() {
        // Deleted rather than generalised, once: the digest used to carry a
        // channel/transport/recipients block. The honest fix for a schema describing
        // something the tool must not do is to remove the schema, not to make it
        // vendor-neutral.
        var offenders = new ArrayList<String>();
        offenders.addAll(scan(
                ConfigFixtures.repositoryRoot().resolve("backend/src/main/resources/leadgen"),
                ".yaml",
                TRANSPORT_KEYS));
        // `demo/` too, and not as an afterthought: it is committed configuration a reader
        // copies from, so a transport key demonstrated there spreads faster than one in the
        // defaults nobody edits.
        offenders.addAll(scan(ConfigFixtures.repositoryRoot().resolve("demo"), ".yaml", TRANSPORT_KEYS));
        assertThat(offenders)
                .as("a configuration key for a channel is an invitation to write the code behind it")
                .isEmpty();
    }

    @Test
    void theFrontendOffersNoSendAffordance() {
        Path frontend = ConfigFixtures.repositoryRoot().resolve("frontend/src/app");
        var offenders = new ArrayList<String>();
        offenders.addAll(scan(frontend, ".ts", SEND_PATHS));
        offenders.addAll(scan(frontend, ".html", SEND_PATHS));
        assertThat(offenders)
                .as("the offer detail is where a send button would look most reasonable")
                .isEmpty();
    }

    private static List<String> scan(Path root, String suffix, List<Pattern> patterns) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(suffix))
                    .forEach(file -> {
                        String content = read(file);
                        patterns.stream()
                                .filter(pattern -> pattern.matcher(content).find())
                                .forEach(pattern ->
                                        offenders.add(root.relativize(file) + " matches " + pattern.pattern()));
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return offenders;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
