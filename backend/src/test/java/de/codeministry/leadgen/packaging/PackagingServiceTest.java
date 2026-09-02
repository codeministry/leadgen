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
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

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
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
    }

    @Test
    void buildsAFolderWithEveryDocument() {
        long id = shortlisted(
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
        long german = shortlisted(
                "Senior Java Entwickler (m/w/d)",
                "Wir suchen für unseren Kunden einen Entwickler mit Erfahrung in Spring Boot.");
        packaging.run();
        assertThat(read(folderOf(german).resolve("cover_letter.txt")))
                .contains("Sehr geehrte Damen und Herren")
                .doesNotContain("Dear Sir");

        reset();
        long english = shortlisted(
                "Senior Java Developer", "Our client is looking for a backend engineer, Spring Boot, remote.");
        packaging.run();
        assertThat(read(folderOf(english).resolve("cover_letter.txt")))
                .contains("Dear Sir or Madam")
                .doesNotContain("Sehr geehrte");
    }

    @Test
    void archivesTheAdAsItWasWhenTheDecisionWasMade() {
        // Portals take listings down. A package without the original is a package nobody
        // can check six months later.
        long id = shortlisted("Senior Java Entwickler (m/w/d)", "Kurzbeschreibung aus dem Newsletter.");
        jdbc.update("UPDATE offer SET full_text = ? WHERE id = ?", "Der vollständige Text der Anzeige.", id);

        packaging.run();

        assertThat(read(folderOf(id).resolve("offer.txt")))
                .contains("Senior Java Entwickler")
                .contains("Kurzbeschreibung aus dem Newsletter")
                .contains("Der vollständige Text der Anzeige");
    }

    @Test
    void writesTheScoreAndItsReasonsIntoMetaJson() throws IOException {
        long id = shortlisted("Senior Java Entwickler (m/w/d)", "Spring Boot, für unseren Kunden.");
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
        long primary = shortlisted("Senior Java Entwickler (m/w/d)", "Spring Boot, für unseren Kunden.");
        long second = shortlisted("Senior Java Entwickler (m/w/d)", "Spring Boot, für unseren Kunden.");
        jdbc.update("UPDATE offer SET duplicate_of_id = ?, portal = 'portal-b' WHERE id = ?", primary, second);

        packaging.run();

        JsonNode meta = JSON.readTree(read(folderOf(primary).resolve("meta.json")));
        assertThat(meta.path("sources")).hasSize(2);
        assertThat(meta.path("sources").toString()).contains("portal-a").contains("portal-b");
    }

    @Test
    void packagesOnlyWhatIsAboveTheThreshold() {
        long shortlisted = shortlisted("Senior Java Entwickler (m/w/d)", "Spring Boot, für unseren Kunden.");
        long review = shortlisted("Java Entwickler (m/w/d)", "Spring Boot, für unseren Kunden.");
        jdbc.update("UPDATE offer SET score_band = 'REVIEW', score_value = 61 WHERE id = ?", review);

        var report = packaging.run();

        assertThat(report.due()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT package_dir FROM offer WHERE id = ?", String.class, shortlisted))
                .isNotNull();
        assertThat(jdbc.queryForObject("SELECT package_dir FROM offer WHERE id = ?", String.class, review))
                .isNull();
    }

    @Test
    void doesNotBuildTheSamePackageTwice() {
        shortlisted("Senior Java Entwickler (m/w/d)", "Spring Boot, für unseren Kunden.");

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
        long id = shortlisted("Senior Java Entwickler (m/w/d)", "Spring Boot, für unseren Kunden.");

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

    private long shortlisted(String title, String description) {
        return jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, description, url, fingerprint, status,
                                   score_value, score_band, location, portal, agency, published_on)
                VALUES (?, ?, ?, ?, 'https://example.invalid/projekt/1', 'fp', 'PASSED', 88, 'SHORTLISTED',
                        'Köln', 'portal-a', 'Acme Consulting GmbH', DATE '2026-08-31')
                RETURNING id
                """,
                Long.class,
                sourceId,
                "ext-" + System.nanoTime(),
                title,
                description);
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
