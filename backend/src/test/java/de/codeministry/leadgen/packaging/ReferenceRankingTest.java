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

import de.codeministry.leadgen.config.model.SkillProfile;
import de.codeministry.leadgen.llm.Vectors;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Which projects a letter pitches, decided without a database and without an endpoint.
 *
 * <p>The vectors are written by hand and point along a circle, so "nearest" is something this
 * test decides rather than something a model decides. What is under test is the blend.
 */
class ReferenceRankingTest {

    private static final SkillProfile.ReferenceProject BANK = project("bank", List.of("Java", "Spring Boot"));
    private static final SkillProfile.ReferenceProject SHOP = project("shop", List.of("Java", "Angular"));
    private static final SkillProfile.ReferenceProject OPS = project("ops", List.of("Kubernetes"));

    private static final SkillProfile PROFILE = profile(BANK, SHOP, OPS);

    @Test
    void keepsTheLexicalChoiceWhenTheAdvertNamesAStack() {
        // The guard that makes this change additive: a project whose stack the advert names is
        // a project the advert asked for, and no similarity outranks that.
        var chosen = ReferenceRanking.choose(
                PROFILE,
                p -> p.id().equals("ops") ? 1 : 0,
                direction(0),
                Map.of("bank", direction(0), "shop", direction(1), "ops", direction(900)),
                2);

        assertThat(ids(chosen)).first().isEqualTo("ops");
    }

    @Test
    void neverLetsSimilarityOutrankARealMatch() {
        // Two overlaps beat one, whatever the vectors say. Stated as its own case because it is
        // the whole of "rules before model" on this path.
        var chosen = ReferenceRanking.choose(
                PROFILE,
                p -> p.id().equals("bank") ? 2 : 1,
                direction(0),
                Map.of("bank", direction(900), "shop", direction(0), "ops", direction(0)),
                2);

        assertThat(ids(chosen)).containsExactly("bank", "shop");
    }

    @Test
    void breaksALexicalTieBySimilarity() {
        // A dozen Spring projects match the word "Java" with the same count, and raw count then
        // leaves the order to whichever the YAML listed first.
        var chosen = ReferenceRanking.choose(
                PROFILE,
                p -> 1,
                direction(0),
                Map.of("bank", direction(500), "shop", direction(1), "ops", direction(900)),
                2);

        assertThat(ids(chosen)).containsExactly("shop", "bank");
    }

    @Test
    void fillsTheSlotsTheLexicalRuleLeftEmpty() {
        // The defect this change exists for: an advert naming no stack token at all produced
        // one reference or none, silently, because `meta.json` records the empty list and
        // nobody reads it before sending.
        var chosen = ReferenceRanking.choose(
                PROFILE,
                p -> 0,
                direction(0),
                Map.of("bank", direction(900), "shop", direction(1), "ops", direction(500)),
                2);

        assertThat(ids(chosen)).containsExactly("shop", "ops");
    }

    @Test
    void fallsBackToTheOldBehaviourWithoutVectors() {
        // No embedding model, a spent budget, an advert that was never indexed: the letter is
        // the letter it was yesterday. Byte for byte, which is what makes the fallback real.
        var chosen = ReferenceRanking.choose(PROFILE, p -> p.id().equals("ops") ? 1 : 0, null, Map.of(), 2);

        assertThat(ids(chosen)).containsExactly("ops");
    }

    @Test
    void doesNotFillASlotFromProjectsNobodyHasAnOpinionAbout() {
        // A zero similarity is "no opinion", not "a weak match". Filling a letter from those is
        // worse than a short letter, because the reader cannot tell the two apart.
        var chosen = ReferenceRanking.choose(PROFILE, p -> 0, direction(0), Map.of("bank", direction(10)), 2);

        assertThat(ids(chosen)).containsExactly("bank");
    }

    @Test
    void neverExceedsTheLimit() {
        var chosen = ReferenceRanking.choose(
                PROFILE,
                p -> 3,
                direction(0),
                Map.of("bank", direction(1), "shop", direction(2), "ops", direction(3)),
                2);

        assertThat(chosen).hasSize(2);
    }

    @Test
    void readsACosineAsASimilarityAndNotAsADistance() {
        // The sign trap this repository already carries a note about, one level down: a vector
        // against itself is 1 and not 0, and reading one as the other ranks everything
        // backwards while still returning plausible numbers.
        assertThat(Vectors.similarity(direction(0), direction(0)))
                .isEqualTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(Vectors.similarity(direction(0), direction(900)))
                .isLessThan(Vectors.similarity(direction(0), direction(1)));
    }

    @Test
    void survivesAVectorThatIsNotThere() {
        assertThat(Vectors.parse(null)).isNull();
        assertThat(Vectors.parse("  ")).isNull();
        assertThat(Vectors.parse("[]")).isNull();
        assertThat(Vectors.parse("[1.5,-0.25,0]")).containsExactly(1.5f, -0.25f, 0f);
    }

    private static List<String> ids(List<SkillProfile.ReferenceProject> chosen) {
        return chosen.stream().map(SkillProfile.ReferenceProject::id).toList();
    }

    private static float[] direction(int step) {
        float[] vector = new float[Vectors.DIMENSIONS];
        double angle = step * Math.PI / 2000;
        vector[0] = (float) Math.cos(angle);
        vector[1] = (float) Math.sin(angle);
        return vector;
    }

    private static SkillProfile.ReferenceProject project(String id, List<String> stack) {
        return new SkillProfile.ReferenceProject(
                id,
                id + " Projekt",
                id + " project",
                YearMonth.of(2023, 4),
                YearMonth.of(2024, 11),
                "Fullstack",
                stack,
                "de",
                "en");
    }

    private static SkillProfile profile(SkillProfile.ReferenceProject... projects) {
        // version, localePrimary, identity, core, strong, peripheral, industries,
        // referenceProjects, languages, cvVariants — only the one this class is about is set.
        return new SkillProfile(1, "de", null, null, null, null, null, List.of(projects), null, null);
    }
}
