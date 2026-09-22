/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.retrieval;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.ingest.extract.TitleNormalizer;
import de.codeministry.leadgen.llm.Vectors;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
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
 * Filling the retrieval column: which offers are due, what text goes out, and what the pass
 * must never touch.
 *
 * <p>Against a real Postgres for the reason {@code OfferEmbedderTest} gives — a vector written
 * as pgvector's own text form has to come back as something the database will compare, and a
 * repository test asserting "the update ran" passes with a column full of unusable strings.
 */
@SpringBootTest
@Testcontainers
class RetrievalIndexServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    private static final WireMockServer MODEL;

    static {
        MODEL = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        MODEL.start();
    }

    @Autowired
    private RetrievalIndexService index;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> CONFIG.toString());
    }

    /**
     * Built once in a static field and handed out, never built inside the supplier: a
     * {@code @DynamicPropertySource} supplier is called once per resolution and not once per
     * context, so one that creates a directory hands out a different one each time and every
     * edit is read from a file nobody loads.
     */
    private static final Path CONFIG = configPointingAtTheStub();

    @AfterAll
    static void stop() {
        MODEL.stop();
    }

    @BeforeEach
    void reset() {
        MODEL.resetAll();
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        jdbc.update("DELETE FROM llm_call_budget");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
    }

    @Test
    void leavesTheDedupeVectorUntouched() {
        // The load-bearing test of this whole stage. `offer.embedding` holds a different text
        // and its merge band was measured against exactly that text; a pass that wrote into it
        // would not fail, it would quietly re-tune deduplication. Compared as a digest over
        // every row rather than per row, so a single rewritten vector anywhere shows up.
        answersWith(Vectors.DIMENSIONS, 2);
        long one = segmented("Senior Java Entwickler (m/w/d)", "Ablösung eines Monolithen.");
        long two = segmented("Angular Entwickler (m/w/d)", "Frontend für ein Versicherungsportal.");
        jdbc.update(
                "UPDATE offer SET embedding = CAST(? AS vector), embedding_model = 'dedupe-model' WHERE id IN (?, ?)",
                Vectors.literal(new float[Vectors.DIMENSIONS]),
                one,
                two);
        String before = dedupeDigest();

        assertThat(index.run().embedded()).isEqualTo(2);

        assertThat(dedupeDigest()).isEqualTo(before);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM offer WHERE embedding_model = 'dedupe-model'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void writesAVectorTheDatabaseCanCompare() {
        answersWith(Vectors.DIMENSIONS, 1);
        segmented("Senior Java Entwickler (m/w/d)", "Ablösung eines Monolithen.");

        assertThat(index.run().embedded()).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                        "SELECT retrieval_embedding <=> retrieval_embedding FROM offer"
                                + " WHERE retrieval_embedding IS NOT NULL",
                        Double.class))
                .isEqualTo(0.0);
        assertThat(jdbc.queryForObject("SELECT retrieval_embedding_model FROM offer", String.class))
                .isEqualTo("test-embed");
    }

    @Test
    void embedsOnlyWhatIsDue() {
        // Three shapes: never embedded, embedded before the advert was last segmented, and
        // embedded after it. The middle one is the case `OfferEmbedder` has no equivalent of —
        // the content stage rewrites the text when a portal changes its markup, and a model
        // comparison cannot see that the text moved while the model name stayed the same.
        answersWith(Vectors.DIMENSIONS, 2);
        long never = segmented("Erste Anzeige", "Eins.");
        long stale = segmented("Zweite Anzeige", "Zwei.");
        long current = segmented("Dritte Anzeige", "Drei.");
        embeddedAt(stale, "test-embed", "now() - interval '2 hours'");
        embeddedAt(current, "test-embed", "now() + interval '2 hours'");

        assertThat(index.run().due()).isEqualTo(2);
        assertThat(isIndexed(never)).isTrue();
        assertThat(model(stale)).isEqualTo("test-embed");
        // Untouched: its vector is newer than the segmentation, so there is nothing to redo.
        assertThat(jdbc.queryForObject(
                        "SELECT retrieval_embedded_at > content_at FROM offer WHERE id = ?", Boolean.class, current))
                .isTrue();
    }

    @Test
    void reEmbedsWhenTheModelNameChanges() {
        // Two models are two spaces, and a row from another one has to be re-embedded rather
        // than compared. The self-healing due shape every other stage uses.
        answersWith(Vectors.DIMENSIONS, 1);
        long offer = segmented("Senior Java Entwickler (m/w/d)", "Ablösung eines Monolithen.");
        embeddedAt(offer, "an-older-model", "now() + interval '2 hours'");

        assertThat(index.run().embedded()).isEqualTo(1);
        assertThat(model(offer)).isEqualTo("test-embed");
    }

    @Test
    void stopsWhenTheBudgetIsSpentAndLeavesTheRestDue() {
        // Nothing is written as embedded that was not embedded. One batch fits in the day's
        // remaining allowance, the rest keep `retrieval_embedded_at IS NULL` and the next run
        // continues where this one stopped — which is what the first nights after switching
        // this on look like, and is the backfill rather than a fault.
        answersWith(Vectors.DIMENSIONS, 32);
        for (int index = 0; index < 40; index++) {
            segmented("Anzeige " + index, "Beschreibung " + index);
        }
        jdbc.update("INSERT INTO llm_call_budget (day, calls) VALUES (current_date, ?)", limit() - 1);

        RetrievalReport report = index.run();

        assertThat(report.due()).isEqualTo(40);
        assertThat(report.embedded()).isEqualTo(32);
        assertThat(report.requests()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM offer WHERE retrieval_embedded_at IS NULL", Integer.class))
                .isEqualTo(8);
    }

    @Test
    void readsTheAdvertAndNotThePortalFurnitureTheContentStageTookOut() {
        // The dilution the character cap guards against is caused by exactly the blocks the
        // content stage removes. Embedding the whole page would put all of it back and make
        // every advert from one agency look alike.
        answersWith(Vectors.DIMENSIONS, 1);
        long offer = insert("Senior Java Entwickler (m/w/d)", "Köln");
        jdbc.update(
                """
                UPDATE offer
                   SET content_blocks = CAST(? AS jsonb),
                       full_text = ?,
                       content_at = now(),
                       status = 'PASSED'
                 WHERE id = ?
                """,
                """
                [{"index":0,"kind":"CONTENT","text":"Ablösung eines Kernbankensystems.","reason":"t","by":"RULE"},
                 {"index":1,"kind":"AGENCY","text":"Acme Consulting GmbH, Amtsgericht Köln HRB 12345.","reason":"t","by":"RULE"}]
                """,
                "Acme Consulting GmbH, Amtsgericht Köln HRB 12345. Jetzt bewerben.",
                offer);

        index.run();

        MODEL.verify(postRequestedFor(urlPathEqualTo("/embeddings"))
                .withRequestBody(matching("(?s).*Kernbankensystems.*"))
                .withRequestBody(notMatching("(?s).*Amtsgericht.*")));
    }

    @Test
    void refusesAModelNarrowerThanTheColumnRatherThanWritingWhatItSent() {
        answersWith(1536, 1);
        segmented("Senior Java Entwickler (m/w/d)", "Ablösung eines Monolithen.");

        assertThat(index.run().embedded()).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM offer WHERE retrieval_embedding IS NOT NULL", Integer.class))
                .isZero();
    }

    @Test
    void keepsTheLeadingDimensionsOfAWiderModelRatherThanRefusingIt() {
        answersWith(Vectors.DIMENSIONS + 2096, 1);
        segmented("Senior Java Entwickler (m/w/d)", "Ablösung eines Monolithen.");

        assertThat(index.run().embedded()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT vector_dims(retrieval_embedding) FROM offer WHERE retrieval_embedding IS NOT NULL",
                        Integer.class))
                .isEqualTo(Vectors.DIMENSIONS);
    }

    @Test
    void survivesAnEndpointThatIsNotThere() {
        MODEL.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(500).withBody("{}")));
        segmented("Senior Java Entwickler (m/w/d)", "Ablösung eines Monolithen.");

        assertThat(index.run().embedded()).isZero();
    }

    @Test
    void ignoresAnOfferEnrichmentNeverReached() {
        // No advert at all is not a vector of an empty string: it is nothing to index, and a
        // row with neither text has no business costing a request.
        answersWith(Vectors.DIMENSIONS, 1);
        insert("Senior Java Entwickler (m/w/d)", "Köln");
        jdbc.update("UPDATE offer SET status = 'PASSED'");

        assertThat(index.run().due()).isZero();
        MODEL.verify(0, postRequestedFor(urlPathEqualTo("/embeddings")));
    }

    private String dedupeDigest() {
        return jdbc.queryForObject(
                "SELECT coalesce(md5(string_agg(embedding::text, '' ORDER BY id)), 'none')"
                        + " FROM offer WHERE embedding IS NOT NULL",
                String.class);
    }

    private int limit() {
        return 300;
    }

    private boolean isIndexed(long offer) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT retrieval_embedding IS NOT NULL FROM offer WHERE id = ?", Boolean.class, offer));
    }

    private String model(long offer) {
        return jdbc.queryForObject("SELECT retrieval_embedding_model FROM offer WHERE id = ?", String.class, offer);
    }

    private void embeddedAt(long offer, String model, String when) {
        jdbc.update(
                "UPDATE offer SET retrieval_embedding = CAST(? AS vector), retrieval_embedding_model = ?,"
                        + " retrieval_embedded_at = " + when + " WHERE id = ?",
                Vectors.literal(new float[Vectors.DIMENSIONS]),
                model,
                offer);
    }

    /** A PASSED offer with a segmented advert, which is what this stage is due on. */
    private long segmented(String title, String advert) {
        long id = insert(title, "Köln");
        jdbc.update(
                """
                UPDATE offer
                   SET content_blocks = CAST(? AS jsonb),
                       full_text = ?,
                       content_at = now(),
                       status = 'PASSED'
                 WHERE id = ?
                """,
                "[{\"index\":0,\"kind\":\"CONTENT\",\"text\":\"%s\",\"reason\":\"t\",\"by\":\"RULE\"}]"
                        .formatted(advert),
                advert,
                id);
        return id;
    }

    private long insert(String title, String location) {
        return jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, description, url, location, portal, fingerprint)
                VALUES (?, ?, ?, 'Teaser.', ?, ?, 'portal-a', ?)
                RETURNING id
                """,
                Long.class,
                sourceId,
                "ext-" + title.hashCode() + "-" + Instant.now().toEpochMilli() + "-" + Math.random(),
                title,
                "https://example.invalid/" + Math.random(),
                location,
                TitleNormalizer.normalize(title));
    }

    private static void answersWith(int width, int count) {
        String vector = IntStream.range(0, width)
                .mapToObj(index -> String.valueOf(index / (double) width))
                .collect(Collectors.joining(","));
        String data = IntStream.range(0, count)
                .mapToObj(
                        index -> "{\"object\":\"embedding\",\"index\":%d,\"embedding\":[%s]}".formatted(index, vector))
                .collect(Collectors.joining(","));
        MODEL.stubFor(post(urlPathEqualTo("/embeddings"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                                """
                                {"object":"list","model":"test-embed",
                                 "usage":{"prompt_tokens":1,"total_tokens":1},
                                 "data":[%s]}
                                """
                                        .formatted(data))));
    }

    /**
     * The shipped defaults with the llm block pointed at the stub and the stage switched on.
     * No vendor is named and no placeholder is left open: the resolver would otherwise fill it
     * from the developer's own `.env`.
     */
    private static Path configPointingAtTheStub() {
        try {
            Path dir = Files.createTempDirectory("leadgen-retrieval");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);

            Path pipeline = dir.resolve("pipeline.yaml");
            String text = Files.readString(pipeline, StandardCharsets.UTF_8);
            text = set(text, "provider", "openai-compatible");
            text = set(text, "base_url", MODEL.baseUrl());
            text = set(text, "api_key", "test-key");
            text = set(text, "embedding", "test-embed");
            // By its placeholder and not by its key: `enabled:` appears on five blocks in this
            // file, and addressing it by name would switch on whichever one comes first.
            text = text.replace("${RETRIEVAL_ENABLED:false}", "true");
            Files.writeString(pipeline, text, StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String set(String yaml, String key, String value) {
        Matcher matcher =
                Pattern.compile("(?m)^([ \\t]*)" + Pattern.quote(key) + ":.*$").matcher(yaml);
        if (!matcher.find()) {
            throw new IllegalStateException(
                    "no `" + key + ":` in the shipped pipeline.yaml — the fixture and the file have drifted");
        }
        return matcher.replaceFirst("$1" + key + ": " + Matcher.quoteReplacement(value));
    }
}
