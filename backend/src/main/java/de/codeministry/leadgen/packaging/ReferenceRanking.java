/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.packaging;

import de.codeministry.leadgen.config.model.SkillProfile;
import de.codeministry.leadgen.llm.Vectors;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * Which of your projects the letter pitches.
 *
 * <h2>Rules before model, and here that is a shape rather than a slogan</h2>
 *
 * <p>The lexical rule decides everything it can decide: a project whose stack the advert names
 * is a project the advert asked for, and no similarity outranks that. The model speaks in
 * exactly the two places the lexical rule has nothing to say.
 *
 * <ol>
 *   <li><b>It breaks ties.</b> A dozen Spring projects all match the word "Java" with the same
 *       count, and raw count then leaves the order to whichever the YAML happened to list
 *       first. Among equals, the nearest to what the advert is <i>about</i> wins.
 *   <li><b>It fills the empty slots.</b> The old rule filtered to {@code overlap > 0} before
 *       taking two, so an advert naming no stack token at all produced <b>one reference or
 *       none</b> — silently, because {@code meta.json} records the empty list and nobody reads
 *       it before sending. That was the defect worth fixing.
 * </ol>
 *
 * <p>Without vectors this is byte for byte the old behaviour, which is what makes the fallback
 * real rather than nominal: no embedding model, a spent budget, an advert with no vector, and
 * the letter is the letter it was yesterday.
 *
 * <p>Pure and static on purpose. What it decides is worth a test that needs no database and no
 * endpoint, and the measurement behind it is {@code docs/samples/measure_references.ts}.
 */
final class ReferenceRanking {

    private ReferenceRanking() {}

    /**
     * At most {@code limit} projects, strongest first.
     *
     * @param profile        the profile whose reference projects are on offer.
     * @param overlap        how many of a project's stack tokens the advert names. The lexical
     *                       rule, passed in because the folding and the haystack belong to the
     *                       caller.
     * @param advertVector   the offer's retrieval vector, or null when it has none.
     * @param projectVectors a vector per project id, empty when this installation cannot embed.
     */
    static List<SkillProfile.ReferenceProject> choose(
            SkillProfile profile,
            ToLongFunction<SkillProfile.ReferenceProject> overlap,
            float[] advertVector,
            Map<String, float[]> projectVectors,
            int limit) {
        if (profile == null || profile.referenceProjects() == null) {
            return List.of();
        }

        record Scored(SkillProfile.ReferenceProject project, long overlap, double similarity) {}

        List<Scored> scored = profile.referenceProjects().stream()
                .map(project -> new Scored(
                        project,
                        overlap.applyAsLong(project),
                        similarity(advertVector, projectVectors.get(project.id()))))
                .toList();

        // Overlap first and similarity only within it: the comparator is the whole rule, and
        // writing it as one sort is what keeps "the model never outranks a real match" true by
        // construction rather than by a later reader's care.
        Comparator<Scored> byOverlapThenSimilarity = Comparator.comparingLong(Scored::overlap)
                .thenComparingDouble(Scored::similarity)
                .reversed();

        List<SkillProfile.ReferenceProject> chosen = new ArrayList<>(limit);
        scored.stream()
                .filter(entry -> entry.overlap() > 0)
                .sorted(byOverlapThenSimilarity)
                .limit(limit)
                .forEach(entry -> chosen.add(entry.project()));

        if (chosen.size() >= limit) {
            return List.copyOf(chosen);
        }

        // The slots the lexical rule left empty. Only a project the model actually placed is
        // eligible: a zero similarity is "no opinion", and filling a letter from projects
        // nobody has an opinion about is worse than a short letter.
        scored.stream()
                .filter(entry -> entry.overlap() == 0 && entry.similarity() > 0)
                .sorted(Comparator.comparingDouble(Scored::similarity).reversed())
                .limit((long) limit - chosen.size())
                .forEach(entry -> chosen.add(entry.project()));

        return List.copyOf(chosen);
    }

    /** Zero means "no opinion", which is what an absent vector on either side is. */
    private static double similarity(float[] advert, float[] project) {
        return advert == null || project == null ? 0 : Vectors.similarity(advert, project);
    }
}
