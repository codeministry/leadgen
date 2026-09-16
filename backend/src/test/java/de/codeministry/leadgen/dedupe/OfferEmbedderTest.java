/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.dedupe;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.ingest.extract.TitleNormalizer;
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

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Filling the vector column: what is embedded, what is skipped, and what happens to an
 * answer of the wrong shape.
 *
 * <p>Against a real Postgres rather than a mocked one, because the half worth proving is
 * that a vector written as pgvector's own text form comes back as something the database
 * will compare. A repository test asserting "the update ran" would pass with a column full
 * of unusable strings.
 */
@SpringBootTest
@Testcontainers
class OfferEmbedderTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    private static final WireMockServer MODEL;

    static {
        MODEL = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        MODEL.start();
    }

    @Autowired
    private OfferEmbedder embedder;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> configPointingAtTheStub().toString());
    }

    @AfterAll
    static void stop() {
        MODEL.stop();
    }

    @BeforeEach
    void reset() {
        MODEL.resetAll();
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        sourceId = jdbc.queryForObject(
            "INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
    }

    @Test
    void writesAVectorTheDatabaseCanCompare() {
        // The whole point of the text form: `'[1,2,3]'::vector` has to arrive as a vector,
        // not as a string that merely looks like one. `<=>` against itself is 0 for a real
        // vector and an error for anything else.
        answersWith(OfferEmbedder.DIMENSIONS, 1);
        insert("Senior Java Entwickler (m/w/d)", "Köln", "Ablösung eines Monolithen.");

        assertThat(embedder.embed(60)).isEqualTo(1);

        Double distance = jdbc.queryForObject(
            "SELECT embedding <=> embedding FROM offer WHERE embedding IS NOT NULL", Double.class);
        assertThat(distance).isEqualTo(0.0);
        assertThat(jdbc.queryForObject("SELECT embedding_model FROM offer", String.class))
            .isEqualTo("test-embed");
    }

    @Test
    void embedsEachOfferOnceAndNotAgainOnTheNextRun() {
        // The pass runs after every ingest, and a vector that is recomputed every night is
        // a bill that grows with the archive rather than with what arrived.
        answersWith(OfferEmbedder.DIMENSIONS, 2);
        insert("Senior Java Entwickler (m/w/d)", "Köln", "Ablösung eines Monolithen.");
        insert("Angular Entwickler (m/w/d)", "Remote", "Frontend für ein Versicherungsportal.");

        assertThat(embedder.embed(60)).isEqualTo(2);
        MODEL.verify(1, postRequestedFor(urlPathEqualTo("/embeddings")));

        assertThat(embedder.embed(60)).isZero();
        MODEL.verify(1, postRequestedFor(urlPathEqualTo("/embeddings")));
    }

    @Test
    void skipsWhatTheExactPassAlreadyResolved() {
        // The exact strategy runs first. A vector for a row already attached to a primary
        // buys nothing and costs the same as one that does.
        answersWith(OfferEmbedder.DIMENSIONS, 1);
        long primary = insert("Senior Java Entwickler (m/w/d)", "Köln", "Eins.");
        long attached = insert("Senior Java Entwickler (m/w/d)", "Köln", "Zwei.");
        jdbc.update("UPDATE offer SET duplicate_of_id = ? WHERE id = ?", primary, attached);

        assertThat(embedder.embed(60)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT embedding IS NULL FROM offer WHERE id = ?", Boolean.class, attached))
            .isTrue();
    }

    @Test
    void skipsWhatHasBeenArchivedOffTheWorkingList() {
        // In a nightly run this changes nothing, because archiving happens after this stage.
        // On a standing backlog it is the whole cost: measured on 13240 offers of which 13232
        // were archived, the window held 11437 rows to embed and 8 of them were still on the
        // working list. The similarity strategies are scoped to the working list as a result,
        // because a row with no vector is invisible to both of them.
        answersWith(OfferEmbedder.DIMENSIONS, 1);
        insert("Senior Java Entwickler (m/w/d)", "Köln", "Eins.");
        long archived = insert("Angular Entwickler (m/w/d)", "Remote", "Zwei.");
        jdbc.update("UPDATE offer SET archived_at = now() WHERE id = ?", archived);

        assertThat(embedder.embed(60)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT embedding IS NULL FROM offer WHERE id = ?", Boolean.class, archived))
            .isTrue();
    }

    @Test
    void sendsTheTitleTheLocationAndTheAdvertsOpening() {
        answersWith(OfferEmbedder.DIMENSIONS, 1);
        insert("Senior Java Entwickler (m/w/d)", "Köln", "Ablösung eines Monolithen.");

        embedder.embed(60);

        MODEL.verify(postRequestedFor(urlPathEqualTo("/embeddings"))
            .withRequestBody(matching("(?s).*Senior Java Entwickler.*"))
            // The field that cost the exact fingerprint 53 correct merges, because a
            // place written two ways is one place and two strings.
            .withRequestBody(matching("(?s).*K.{1,6}ln.*"))
            .withRequestBody(matching("(?s).*Monolithen.*")));
    }

    @Test
    void refusesAModelNarrowerThanTheColumnRatherThanWritingWhatItSent() {
        // A 1536-dimensional model against a 2000-wide column is a configuration mistake, and
        // Postgres would report it as a dimension mismatch naming neither the model nor the
        // key that chose it. Nothing is written, so the next run with the right model still
        // finds work to do.
        answersWith(1536, 1);
        insert("Senior Java Entwickler (m/w/d)", "Köln", "Ablösung eines Monolithen.");

        assertThat(embedder.embed(60)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM offer WHERE embedding IS NOT NULL", Integer.class))
            .isZero();
    }

    @Test
    void keepsTheLeadingDimensionsOfAWiderModelRatherThanRefusingIt() {
        // `qwen3-embedding:8b` returns 4096 and pgvector will not index past 2000, so the
        // choice is truncate or do without the model that actually separates this market.
        // Measured on 2222 adverts, cutting it to 2000 moves the 0.85 band by two percent.
        // The vector still has to arrive as a vector, which is what `<=>` proves.
        answersWith(OfferEmbedder.DIMENSIONS + 2096, 1);
        insert("Senior Java Entwickler (m/w/d)", "Köln", "Ablösung eines Monolithen.");

        assertThat(embedder.embed(60)).isEqualTo(1);

        Integer width = jdbc.queryForObject(
            "SELECT vector_dims(embedding) FROM offer WHERE embedding IS NOT NULL", Integer.class);
        assertThat(width).isEqualTo(OfferEmbedder.DIMENSIONS);
        assertThat(jdbc.queryForObject(
                "SELECT embedding <=> embedding FROM offer WHERE embedding IS NOT NULL", Double.class))
            .isEqualTo(0.0);
    }

    @Test
    void survivesAnEndpointThatIsNotThere() {
        // One unreachable endpoint must not end a run: deduplication keeps whatever the
        // deterministic pass did.
        MODEL.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(500).withBody("{}")));
        insert("Senior Java Entwickler (m/w/d)", "Köln", "Ablösung eines Monolithen.");

        assertThat(embedder.embed(60)).isZero();
    }

    @Test
    void composesTheTextItEmbedsFromWhatExistsAtThisPointInThePipeline() {
        // Everything else on an offer comes from enrichment, which runs after this.
        String composed = OfferEmbedder.text("Java Entwickler", "Köln", "x".repeat(2000));

        assertThat(composed).startsWith("Java Entwickler\nKöln\n");
        assertThat(composed).hasSize("Java Entwickler\nKöln\n".length() + OfferEmbedder.DESCRIPTION_CHARS);
        // A missing location is a missing line, never the word "null" inside the text that
        // gets embedded.
        assertThat(OfferEmbedder.text("Java Entwickler", null, null)).isEqualTo("Java Entwickler");
    }

    @Test
    void writesTheVectorInPgvectorsOwnTextForm() {
        assertThat(OfferEmbedder.literal(new float[]{1.5f, -0.25f, 0f})).isEqualTo("[1.5,-0.25,0.0]");
    }

    private long insert(String title, String location, String description) {
        return jdbc.queryForObject(
            """
                INSERT INTO offer (source_id, external_id, title, description, url, location, portal, fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
            Long.class,
            sourceId,
            "ext-" + title.hashCode() + "-" + description.hashCode(),
            title,
            description,
            "https://example.invalid/" + Instant.now().toEpochMilli(),
            location,
            "portal-a",
            TitleNormalizer.normalize(title));
    }

    /**
     * {@code count} embeddings of {@code width} dimensions each. Written out rather than
     * templated from the request: the values do not matter, and what is under test is the
     * shape, the round trip and the refusal.
     */
    private static void answersWith(int width, int count) {
        String vector = IntStream.range(0, width)
            .mapToObj(index -> String.valueOf(index / (double) width))
            .collect(Collectors.joining(","));
        String data = IntStream.range(0, count)
            .mapToObj(index -> "{\"object\":\"embedding\",\"index\":%d,\"embedding\":[%s]}"
                .formatted(index, vector))
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
     * The shipped defaults with the llm block pointed at the stub, the same fixture shape
     * {@code ScoringWithAModelTest} uses. No vendor is named, and no placeholder is left
     * open: the resolver would otherwise fill it from the developer's own `.env`.
     */
    private static Path configPointingAtTheStub() {
        try {
            Path dir = Files.createTempDirectory("leadgen-embedding");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);

            Path pipeline = dir.resolve("pipeline.yaml");
            String text = Files.readString(pipeline, StandardCharsets.UTF_8);
            text = set(text, "provider", "openai-compatible");
            text = set(text, "base_url", MODEL.baseUrl());
            text = set(text, "api_key", "test-key");
            text = set(text, "embedding", "test-embed");
            Files.writeString(pipeline, text, StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Addressed by key rather than by a literal carrying the file's own alignment: a
     * reformat collapsed that padding once and the replacement silently matched nothing.
     */
    private static String set(String yaml, String key, String value) {
        Matcher matcher =
            Pattern.compile("(?m)^([ \\t]*)" + Pattern.quote(key) + ":.*$").matcher(yaml);
        if (!matcher.find()) {
            throw new IllegalStateException(
                "no `" + key + ":` in the shipped pipeline.yaml — the fixture and the file have drifted");
        }
        // `$1` keeps the line's own indentation; the value is quoted because a URL carries
        // characters a replacement string would otherwise read as group references.
        return matcher.replaceFirst("$1" + key + ": " + Matcher.quoteReplacement(value));
    }
}
