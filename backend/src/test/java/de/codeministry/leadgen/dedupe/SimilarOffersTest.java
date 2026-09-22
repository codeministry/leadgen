/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.dedupe;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.ingest.extract.TitleNormalizer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
 * The two similarity strategies, against a real pgvector.
 *
 * <p>No model anywhere: the vectors are written by hand as unit vectors at a chosen angle,
 * so the cosine distance between two of them is exactly {@code 1 - cos(angle)} and every
 * threshold in this file is arithmetic rather than a hope about what an embedding of a
 * German job advert happens to look like.
 */
@SpringBootTest
@Testcontainers
class SimilarOffersTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    /**
     * The two thresholds the shipped `matching-rules.yaml` carries, as similarities.
     */
    private static final double MERGE_AT = 0.97;

    private static final double FLAG_AT = 0.95;

    private static final String MODEL = "test-embed";

    @Autowired
    private SimilarOffers similar;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> shippedDefaults().toString());
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
    }

    @Test
    void mergesThePairInsideTheThresholdAndLeavesTheRestAlone() {
        long oldest = insert("Senior Java Entwickler", 0, minutesAgo(60));
        // 10 degrees: cosine 0.985, distance 0.015, inside the 0.08 the merge threshold means.
        long near = insert("Java Entwickler Senior (m/w/d)", 10, minutesAgo(30));
        // 90 degrees: nothing in common at all.
        long far = insert("Angular Entwickler", 90, minutesAgo(20));

        assertThat(similar.merge(60, MERGE_AT)).isEqualTo(1);

        assertThat(primaryOf(near)).isEqualTo(oldest);
        assertThat(primaryOf(oldest)).isNull();
        assertThat(primaryOf(far)).isNull();
    }

    @Test
    void theOlderOfAPairIsThePrimary() {
        // Antisymmetric by construction, which is the whole reason a second run is stable:
        // the pair always resolves the same way round.
        long newer = insert("Java Entwickler", 8, minutesAgo(5));
        long older = insert("Senior Java Entwickler", 0, minutesAgo(90));

        similar.merge(60, MERGE_AT);

        assertThat(primaryOf(newer)).isEqualTo(older);
        assertThat(primaryOf(older)).isNull();
    }

    @Test
    void movesNothingOnASecondRun() {
        insert("Senior Java Entwickler", 0, minutesAgo(60));
        insert("Java Entwickler Senior", 10, minutesAgo(30));

        assertThat(similar.merge(60, MERGE_AT)).isEqualTo(1);
        assertThat(similar.merge(60, MERGE_AT)).isZero();
    }

    @Test
    void marksWhatIsCloseWithoutMergingIt() {
        // 16 degrees: cosine 0.961. Outside the merge threshold, inside the flag one — the
        // band the second strategy exists for, and a narrow one: at the measured thresholds a
        // flag lives between 14.1 and 18.2 degrees.
        long oldest = insert("Senior Java Entwickler", 0, minutesAgo(60));
        long close = insert("Java Backend Entwickler", 16, minutesAgo(30));

        assertThat(similar.merge(60, MERGE_AT)).isZero();
        assertThat(similar.flag(60, FLAG_AT)).isEqualTo(1);

        assertThat(primaryOf(close)).isNull();
        assertThat(possibleDuplicateOf(close)).isEqualTo(oldest);
    }

    @Test
    void leavesAMergedOfferUnmarked() {
        // A maybe beside a yes is noise: the offer already has an answer.
        insert("Senior Java Entwickler", 0, minutesAgo(60));
        long near = insert("Java Entwickler Senior", 10, minutesAgo(30));

        similar.merge(60, MERGE_AT);
        similar.flag(60, FLAG_AT);

        assertThat(possibleDuplicateOf(near)).isNull();
    }

    @Test
    void shortensAChainSoNoPrimaryIsItselfAttached() {
        // Similarity is not transitive, so one statement can leave A on B while B goes to C.
        // Each step here is 10 degrees (cosine 0.985), and only the neighbouring pairs are
        // inside the threshold — A to C is 20 degrees, cosine 0.940, which is not.
        long first = insert("Senior Java Entwickler", 0, minutesAgo(90));
        long second = insert("Java Entwickler Senior", 10, minutesAgo(60));
        long third = insert("Entwickler Java (m/w/d)", 20, minutesAgo(30));

        similar.merge(60, MERGE_AT);

        assertThat(primaryOf(second)).isEqualTo(first);
        assertThat(primaryOf(third)).isEqualTo(first);
        assertThat(primaryOf(first)).isNull();
    }

    @Test
    void comparesOnlyVectorsThatCameFromTheSameModel() {
        // Two vectors from two models are not far apart or close together; they are numbers
        // from different spaces, and the cosine between them is a number rather than an error.
        long first = insert("Senior Java Entwickler", 0, minutesAgo(60));
        long second = insert("Java Entwickler Senior", 10, minutesAgo(30));
        jdbc.update("UPDATE offer SET embedding_model = 'another-model' WHERE id = ?", second);

        assertThat(similar.merge(60, MERGE_AT)).isZero();
        assertThat(primaryOf(second)).isNull();
        assertThat(first).isPositive();
    }

    @Test
    void ignoresWhatFellOutOfTheWindow() {
        insert("Senior Java Entwickler", 0, Instant.now().minus(Duration.ofDays(90)));
        long recent = insert("Java Entwickler Senior", 10, minutesAgo(30));

        assertThat(similar.merge(60, MERGE_AT)).isZero();
        assertThat(primaryOf(recent)).isNull();
    }

    private long insert(String title, double degrees, Instant ingestedAt) {
        long id = jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, url, portal, fingerprint, ingested_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                sourceId,
                "ext-" + title.hashCode() + "-" + degrees,
                title,
                "https://example.invalid/" + Math.round(degrees) + "-" + ingestedAt.toEpochMilli(),
                "portal-a",
                // Deliberately unique: what is under test is the similarity pass, and a shared
                // fingerprint would let the exact strategy answer first in a real run.
                TitleNormalizer.normalize(title),
                java.sql.Timestamp.from(ingestedAt));
        jdbc.update(
                "UPDATE offer SET embedding = CAST(? AS vector), embedding_model = ? WHERE id = ?",
                unit(degrees),
                MODEL,
                id);
        return id;
    }

    /**
     * A unit vector at {@code degrees} in the first two dimensions and zero everywhere else,
     * so the cosine distance between two of them is exactly {@code 1 - cos(difference)}.
     */
    private static String unit(double degrees) {
        double radians = Math.toRadians(degrees);
        float[] vector = new float[OfferEmbedder.DIMENSIONS];
        vector[0] = (float) Math.cos(radians);
        vector[1] = (float) Math.sin(radians);
        return OfferEmbedder.literal(vector);
    }

    private Long primaryOf(long id) {
        return jdbc.queryForObject("SELECT duplicate_of_id FROM offer WHERE id = ?", Long.class, id);
    }

    private Long possibleDuplicateOf(long id) {
        return jdbc.queryForObject("SELECT possible_duplicate_of_id FROM offer WHERE id = ?", Long.class, id);
    }

    private static Instant minutesAgo(int minutes) {
        return Instant.now().minus(Duration.ofMinutes(minutes));
    }

    private static Path shippedDefaults() {
        try {
            Path dir = Files.createTempDirectory("leadgen-similar");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
