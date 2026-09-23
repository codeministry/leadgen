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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * ISC-51: an offer above the threshold produces a folder with the cover letter, the CV
 * for the ad's language, the archived original and a `meta.json`.
 */
@SpringBootTest
@Testcontainers
class PackagingServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Path packagesDir;
    private static Path cvFile;

    @Autowired
    private PackagingService packaging;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> configWithARealCv().toString());
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM offer_score_reason");
        // Before the offers: the application references them, and the event references the
        // application. The cascade only runs from the application downwards.
        jdbc.update("DELETE FROM application");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
    }

    @Test
    void buildsAFolderWithEveryDocument() {
        long id = requested(
                "Senior Java Entwickler Spring Boot (m/w/d)",
                "Wir suchen für unseren Kunden einen Entwickler mit Erfahrung in Spring Boot.");
        reason(id, "core_skill_overlap", "2 of 2 core skills named: Java, Spring Boot", 45);

        var report = packaging.run();

        assertThat(report.built()).isEqualTo(1);
        Path folder = report.folders().getFirst();
        assertThat(files(folder))
                .contains(
                        "cover_letter.txt",
                        "offer.txt",
                        "meta.json",
                        cvFile.getFileName().toString());
    }

    @Test
    void writesTheCoverLetterInTheLanguageOfTheAd() {
        long german = requested(
                "Senior Java Entwickler (m/w/d)",
                "Wir suchen für unseren Kunden einen Entwickler mit Erfahrung in Spring Boot.");
        packaging.run();
        assertThat(read(folderOf(german).resolve("cover_letter.txt")))
                .contains("Sehr geehrte Damen und Herren")
                .doesNotContain("Dear Sir")
                // The salutation used to be the only thing under test, and the reference
                // projects went out in whichever language the profile happened to be
                // written in. A German advert answered with an English project title is
                // the half of the letter a reader notices first.
                .contains("Beispielprojekt")
                .doesNotContain("Example project");

        reset();
        long english = requested(
                "Senior Java Developer", "Our client is looking for a backend engineer, Spring Boot, remote.");
        packaging.run();
        assertThat(read(folderOf(english).resolve("cover_letter.txt")))
                .contains("Dear Sir or Madam")
                .doesNotContain("Sehr geehrte")
                .contains("Example project")
                .doesNotContain("Beispielprojekt");
    }

    @Test
    void namesThePortalAsTheSourceAndNotTheAgency() {
        // `agency` is the company writing the advert, so it is who the letter is addressed
        // to — not where it was found. Naming it after "über" told the recruiter that we
        // came across their own advert through them.
        long id = requested(
                "Senior Java Entwickler (m/w/d)", "Wir suchen einen Entwickler mit Erfahrung in Spring Boot.");
        packaging.run();
        assertThat(read(folderOf(id).resolve("cover_letter.txt")))
                .contains("Über portal-a bin ich auf")
                .doesNotContain("Acme Consulting GmbH");
    }

    @Test
    void rendersEveryLineFlushLeft() {
        // Freemarker drops a line that holds nothing but a directive; it keeps the
        // indentation of a text line inside an <#if> or a <#list>. Every paragraph of this
        // letter sits in one, so the whole body used to reach the client indented by four
        // spaces and nothing in the suite could see it.
        long id = requested(
                "Senior Java Entwickler (m/w/d)", "Für unseren Kunden suchen wir Spring Boot und Kubernetes.");
        packaging.run();
        assertThat(read(folderOf(id).resolve("cover_letter.txt")).lines())
                .noneMatch(line -> line.startsWith(" ") || line.startsWith("\t"));
    }

    @Test
    void writesTheStartDateTheWayTheLanguageOfTheLetterWritesIt() {
        long id = requested(
                "Senior Java Entwickler (m/w/d)", "Wir suchen einen Entwickler, das Projekt startet fest terminiert.");
        jdbc.update("UPDATE offer SET starts_on = DATE '2026-10-01' WHERE id = ?", id);
        packaging.run();
        assertThat(read(folderOf(id).resolve("cover_letter.txt")))
                .contains("Ein Einstieg zum 01.10.2026 ist möglich.")
                .doesNotContain("2026-10-01");
    }

    @Test
    void archivesTheAdAsItWasWhenTheDecisionWasMade() {
        // Portals take listings down. A package without the original is a package nobody
        // can check six months later.
        long id = requested("Senior Java Entwickler (m/w/d)", "Kurzbeschreibung aus dem Newsletter.");
        jdbc.update("UPDATE offer SET full_text = ? WHERE id = ?", "Der vollständige Text der Anzeige.", id);

        packaging.run();

        assertThat(read(folderOf(id).resolve("offer.txt")))
                .contains("Senior Java Entwickler")
                .contains("Kurzbeschreibung aus dem Newsletter")
                .contains("Der vollständige Text der Anzeige");
    }

    @Test
    void readsASegmentedAdvertThroughTheBlocksAndNotThroughTheRawRow() throws IOException {
        // The regression this test exists for: `content_blocks` is `jsonb` and `tags` is
        // `TEXT[]`, so a row read with `listOfRows()` hands the driver's `PGobject` and
        // `PgArray` straight on. The cast on the first threw for every advert that had been
        // segmented, the per-offer catch turned that into a counter, and `package_dir` was
        // never written — which on screen is every offer above the threshold reporting that
        // it has no package. Every fixture before this one set `full_text` alone, which is
        // why the suite stayed green.
        long id = requested("Senior Entwickler (m/w/d)", "Kurzbeschreibung aus dem Newsletter.");
        jdbc.update(
                """
                UPDATE offer
                SET full_text = ?, tags = ?::text[], content_blocks = ?::jsonb
                WHERE id = ?
                """,
                "Wir suchen fuer unseren Kunden einen Entwickler mit Erfahrung in Spring Boot.\n\nJava",
                "{Java,Angular}",
                """
                [{"index":0,
                  "text":"Wir suchen fuer unseren Kunden einen Entwickler mit Erfahrung in Spring Boot.",
                  "kind":"CONTENT","reason":null,"by":"MODEL"},
                 {"index":1,"text":"Java","kind":"TAXONOMY",
                  "reason":"the portal's own tag cloud","by":"MODEL"}]
                """,
                id);

        var report = packaging.run();

        assertThat(report.built()).isEqualTo(1);
        JsonNode meta = JSON.readTree(read(folderOf(id).resolve("meta.json")));
        assertThat(meta.path("language").asText()).isEqualTo("de");
        // Spring Boot is in the advert, Java is only in the block the model called a tag
        // cloud. Read through `full_text` both would match, which is the defect segmentation
        // exists to fix and which packaging has to honour too.
        assertThat(meta.path("matchedSkills").toString())
                .contains("Spring Boot")
                .doesNotContain("Java");
    }

    @Test
    void writesTheScoreAndItsReasonsIntoMetaJson() throws IOException {
        long id = requested("Senior Java Entwickler (m/w/d)", "Spring Boot, für unseren Kunden.");
        reason(id, "core_skill_overlap", "2 of 2 core skills named: Java, Spring Boot", 45);
        reason(id, "vague_description", "team size left open", -10);

        packaging.run();

        JsonNode meta = JSON.readTree(read(folderOf(id).resolve("meta.json")));
        assertThat(meta.path("score").asInt()).isEqualTo(88);
        assertThat(meta.path("band").asText()).isEqualTo("SHORTLISTED");
        assertThat(meta.path("language").asText()).isEqualTo("de");
        assertThat(meta.path("reasons")).hasSize(2);
        assertThat(meta.path("reasons").get(0).path("label").asText()).contains("core skills named");
        assertThat(meta.path("matchedSkills").toString()).contains("Spring Boot");
    }

    @Test
    void namesEverySourceOfADuplicateCluster() throws IOException {
        // One project advertised by three portals is one package, and it says which three.
        long primary = requested("Senior Java Entwickler (m/w/d)", "Spring Boot, für unseren Kunden.");
        long second = shortlisted("Senior Java Entwickler (m/w/d)", "Spring Boot, für unseren Kunden.");
        jdbc.update("UPDATE offer SET duplicate_of_id = ?, portal = 'portal-b' WHERE id = ?", primary, second);

        packaging.run();

        JsonNode meta = JSON.readTree(read(folderOf(primary).resolve("meta.json")));
        assertThat(meta.path("sources")).hasSize(2);
        assertThat(meta.path("sources").toString()).contains("portal-a").contains("portal-b");
    }

    @Test
    void packagesOnlyWhatSomebodyAskedFor() {
        // Reaching the shortlist is the tool's opinion and buys a card, not a folder. What
        // buys the folder is a person moving that card to PACKAGED.
        long asked = requested("Senior Java Entwickler (m/w/d)", "Spring Boot, für unseren Kunden.");
        long waiting = shortlisted("Java Entwickler (m/w/d)", "Spring Boot, für unseren Kunden.");
        jdbc.update("INSERT INTO application (offer_id, status) VALUES (?, 'NEW')", waiting);

        var report = packaging.run();

        assertThat(report.due()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT package_dir FROM offer WHERE id = ?", String.class, asked))
                .isNotNull();
        assertThat(jdbc.queryForObject("SELECT package_dir FROM offer WHERE id = ?", String.class, waiting))
                .isNull();
    }

    @Test
    void packagesAnOfferTheBandWouldNotHaveShortlisted() {
        // The band stopped being the gate on purpose: an operator may decide to answer a
        // REVIEW, and refusing them the folder would leave a PACKAGED application with
        // nothing behind it — the one state the transition rule exists to prevent.
        long id = requested("Java Entwickler (m/w/d)", "Spring Boot, für unseren Kunden.");
        jdbc.update("UPDATE offer SET score_band = 'REVIEW', score_value = 61 WHERE id = ?", id);

        assertThat(packaging.run().built()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT package_dir FROM offer WHERE id = ?", String.class, id))
                .isNotNull();
    }

    @Test
    void buildsForOneOfferOnDemand() {
        long asked = requested("Senior Java Entwickler (m/w/d)", "Spring Boot, für unseren Kunden.");
        long alsoAsked = requested("Java Entwickler Spring (m/w/d)", "Spring Boot, für unseren Kunden.");

        assertThat(packaging.buildFor(asked).built()).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT package_dir FROM offer WHERE id = ?", String.class, alsoAsked))
                .isNull();
        // And the retry pass in the run picks up the one the listener did not reach.
        assertThat(packaging.run().built()).isEqualTo(1);
    }

    @Test
    void doesNotBuildTheSamePackageTwice() {
        requested("Senior Java Entwickler (m/w/d)", "Spring Boot, für unseren Kunden.");

        assertThat(packaging.run().built()).isEqualTo(1);
        assertThat(packaging.run().due()).isZero();
    }

    @Test
    void recordsAMissingCvRatherThanFailing() {
        // A package without the CV is still most of the work; the operator drops the file
        // in beside it. Failing would cost the cover letter and the archive as well.
        try {
            Files.delete(cvFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        long id = requested("Senior Java Entwickler (m/w/d)", "Spring Boot, für unseren Kunden.");

        assertThat(packaging.run().built()).isEqualTo(1);
        assertThat(files(folderOf(id))).contains("cv-MISSING.txt", "cover_letter.txt", "meta.json");
        writeCv();
    }

    private Path folderOf(long offerId) {
        return Path.of(jdbc.queryForObject("SELECT package_dir FROM offer WHERE id = ?", String.class, offerId));
    }

    private static java.util.List<String> files(Path folder) {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A shortlisted offer somebody has asked for a package for — the only thing this stage
     * builds anything for. The band no longer decides that; the application's status does.
     */
    private long requested(String title, String description) {
        long id = shortlisted(title, description);
        jdbc.update("INSERT INTO application (offer_id, status) VALUES (?, 'PACKAGED')", id);
        return id;
    }

    private long shortlisted(String title, String description) {
        return jdbc.queryForObject("""
            INSERT INTO offer (source_id, external_id, title, description, url, fingerprint, status,
                               score_value, score_band, location, portal, agency, published_on)
            VALUES (?, ?, ?, ?, 'https://example.invalid/projekt/1', 'fp', 'PASSED', 88, 'SHORTLISTED',
                    'Köln', 'portal-a', 'Acme Consulting GmbH', DATE '2026-08-31')
            RETURNING id
            """, Long.class, sourceId, "ext-" + System.nanoTime(), title, description);
    }

    private void reason(long offerId, String factor, String label, int points) {
        Integer next = jdbc.queryForObject(
                "SELECT coalesce(max(position) + 1, 0) FROM offer_score_reason WHERE offer_id = ?",
                Integer.class,
                offerId);
        jdbc.update(
                "INSERT INTO offer_score_reason (offer_id, factor, label, points, position) VALUES (?, ?, ?, ?, ?)",
                offerId,
                factor,
                label,
                points,
                next);
    }

    private static void writeCv() {
        try {
            Files.writeString(cvFile, "a PDF, in spirit", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The shipped defaults, with a CV that exists and a packages directory of our own.
     *
     * <p><b>Built once and remembered.</b> A {@code @DynamicPropertySource} supplier is called
     * every time the property is resolved, not once per context, so anything with a side
     * effect in it happens again the moment something else reads `leadgen.config-dir` — a
     * second temp directory, and `cvFile` pointing at a file the application never opens.
     * The test then deletes a CV nobody was going to read and the package has one anyway.
     */
    private static Path configDirectory;

    private static synchronized Path configWithARealCv() {
        if (configDirectory != null) {
            return configDirectory;
        }
        try {
            Path dir = Files.createTempDirectory("leadgen-packaging");
            dir.toFile().deleteOnExit();
            packagesDir = Files.createTempDirectory("leadgen-packages");
            packagesDir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);

            Path documents = Files.createDirectories(dir.resolve("documents"));
            cvFile = documents.resolve("cv-de.pdf");
            writeCv();

            Path pipeline = dir.resolve("pipeline.yaml");
            Files.writeString(
                    pipeline,
                    Files.readString(pipeline, StandardCharsets.UTF_8)
                            .replace("output_dir: ${PACKAGES_DIR:./packages}", "output_dir: " + packagesDir),
                    StandardCharsets.UTF_8);

            Path profile = dir.resolve("skill-profile.yaml");
            String text = Files.readString(profile, StandardCharsets.UTF_8);
            text = text.substring(0, text.indexOf("cv_variants:"))
                    + "cv_variants:\n"
                    + "  de: { file: \"" + cvFile + "\", default: true }\n"
                    + "  en: { file: \"" + cvFile + "\" }\n";
            Files.writeString(profile, text, StandardCharsets.UTF_8);
            configDirectory = dir;
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
