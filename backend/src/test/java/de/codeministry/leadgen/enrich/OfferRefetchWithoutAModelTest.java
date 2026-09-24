/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.enrich;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The fetch button on a fresh clone: no language model configured anywhere.
 *
 * <p>A class of its own because a context has one configuration, and {@link OfferRefetchTest}
 * needs one with a model. Rules before model: the ad is fetched and stored and the offer is
 * rule-scored, and only the judged half is missing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OfferRefetchWithoutAModelTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    private static final WireMockServer PORTAL =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    /** Created once: a {@code @DynamicPropertySource} supplier may run more than once. */
    private static final Path CONFIG = keylessDefaults();

    private static final String AD_HTML = """
        <html><body>
          <h1>Senior Java Entwickler (m/w/d)</h1>
          <article>
            Für ein Logistikunternehmen suchen wir Verstärkung: Java 21 und Spring Boot.
            Stundensatz 95 EUR/h, Laufzeit 12 Monate, 80 % remote.
          </article>
        </body></html>
        """;

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private FetchWindow window;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    @BeforeAll
    static void startPortal() {
        PORTAL.start();
        WireMock.configureFor("localhost", PORTAL.port());
    }

    @AfterAll
    static void stopPortal() {
        PORTAL.stop();
    }

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG::toString);
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM offer_score_reason");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM fetched_page");
        jdbc.update("DELETE FROM source");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
        PORTAL.resetAll();
        window.clear();
        PORTAL.stubFor(get(urlEqualTo("/robots.txt")).willReturn(aResponse().withStatus(404)));
    }

    @Test
    void storesTheAdAndRuleScoresItWithNoModelConfigured() {
        // ISC-249. Nothing to ask, so the judge is absent — and that must not read as a failed
        // fetch, nor leave the offer scored against the summary it had before. The ad is
        // stored, and the deterministic half of the score is written from it.
        String path = "/projekt/keyless";
        long id = unfetchedOffer(path);
        stubFor(get(urlEqualTo(path)).willReturn(aResponse().withBody(AD_HTML)));
        Timestamp start = jdbc.queryForObject("SELECT now()", Timestamp.class);

        assertThat(mvc.post().uri("/api/v1/offers/{id}/fetch", id)).hasStatusOk();

        assertThat(jdbc.queryForObject("SELECT full_text FROM offer WHERE id = ?", String.class, id))
                .contains("Logistikunternehmen");
        assertThat(jdbc.queryForObject("SELECT scored_at FROM offer WHERE id = ?", Timestamp.class, id))
                .isAfter(start);
        assertThat(jdbc.queryForObject("SELECT score_model FROM offer WHERE id = ?", String.class, id))
                .isNull();
        // The rule half, read from the fetched ad. The title says Java as well, so the factor
        // alone would pass on the title; Spring Boot is stated by the page and nowhere else.
        assertThat(jdbc.queryForList("SELECT factor FROM offer_score_reason WHERE offer_id = ?", String.class, id))
                .contains("core_skill_overlap")
                .doesNotContain("role_fit");
        assertThat(jdbc.queryForObject(
                        "SELECT label FROM offer_score_reason WHERE offer_id = ? AND factor = 'core_skill_overlap'",
                        String.class,
                        id))
                .contains("Spring Boot");
    }

    private long unfetchedOffer(String path) {
        return jdbc.queryForObject("""
            INSERT INTO offer (source_id, external_id, title, url, fingerprint, status,
                               enriched_at, enrichment_note)
            VALUES (?, ?, 'Senior Java Entwickler (m/w/d)', ?, 'senior java entwickler', 'PASSED',
                    now(), 'status 403')
            RETURNING id
            """, Long.class, sourceId, path, PORTAL.baseUrl() + path);
    }

    /**
     * The shipped defaults with every {@code ${LLM_*}} emptied, for the reason
     * {@code ScoringWithoutAModelTest.shippedDefaults} gives: otherwise a key in the developer's
     * {@code .env} turns "no model configured" into a real call.
     */
    private static Path keylessDefaults() {
        try {
            Path dir = Files.createTempDirectory("leadgen-refetch-nomodel");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);
            Path pipeline = dir.resolve("pipeline.yaml");
            Files.writeString(pipeline, Files.readString(pipeline).replaceAll("\\$\\{LLM_[A-Z_]+(?::[^}]*)?}", "''"));
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
