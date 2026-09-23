/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * The operator's own skills, industries and reference projects.
 *
 * <p>Until now this file was only checked for existence. The hard filter needs it:
 * "does this offer name a skill I actually have" cannot be answered from
 * `matching-rules.yaml`, which describes the rules, not the person. Scoring will need
 * the weights and the cover letter the reference projects, so the whole file is bound
 * rather than the two fields the filter reads today.
 *
 * <p>The `title_*` and `pitch_*` fields are content, not repository language: they end up
 * verbatim in a cover letter, in the language of the advert.
 *
 * @param localePrimary the language the profile is written for, not a hard filter.
 */
public record SkillProfile(
        @NotNull Integer version,
        String localePrimary,
        @Valid Identity identity,
        List<@Valid Skill> core,
        List<@Valid Skill> strong,
        List<@Valid Skill> peripheral,
        List<@Valid Industry> industries,
        List<@Valid ReferenceProject> referenceProjects,
        List<@Valid Language> languages,
        Map<String, @Valid CvVariant> cvVariants,
        List<@Valid Topic> interestTopics,
        List<@Valid Topic> disinterestTopics) {

    /**
     * Never null, so a profile written before the two topic lists existed scores as it did.
     */
    public List<Topic> interestTopicsOrEmpty() {
        return interestTopics == null ? List.of() : interestTopics;
    }

    public List<Topic> disinterestTopicsOrEmpty() {
        return disinterestTopics == null ? List.of() : disinterestTopics;
    }

    public record Identity(
            String name,
            String brand,
            String base,
            String freelanceSince,
            String experienceSince,
            List<String> roles,
            String seniority) {}

    /**
     * @param aliases the spellings an ad uses for the same thing. The filter matches on
     *                these as well as on the name, which is why "Spring", "Spring Data" and
     *                "Springboot" all count as Spring Boot without three entries.
     * @param since   the year it was first used in earnest. Not read by the filter; scoring
     *                turns it into depth.
     */
    public record Skill(
            @NotBlank String skill, @Min(1) @Max(10) int weight, Integer since, List<String> aliases) {}

    /**
     * @param match the words a job advert uses for this industry. The name is the
     *              repository's language and the adverts are German, so {@code Insurance} matched
     *              nothing at all until this existed — the factor fired on 0 of 101 scored offers.
     *              Same shape and same reason as {@link Skill#aliases()}. Empty falls back to the
     *              name, so a profile written before this behaves as it did.
     */
    public record Industry(
            @NotBlank String name, @Min(1) @Max(10) int weight, String note, List<String> match) {}

    /**
     * Something the operator wants more of, or none of, whatever the stack. A skill says what
     * he can do and an industry where he has done it; a topic says what he is looking for,
     * which neither of the other two can express and which an advert states in its own words.
     *
     * @param aliases the words an advert uses for it. The name is always tried as well, the
     *                same fallback {@link Industry#match()} has, so a topic written as a single
     *                word needs no list at all.
     */
    public record Topic(
            @NotBlank String name, @Min(1) @Max(10) int weight, List<String> aliases) {

        /**
         * The name first, then every alias, without blanks or repeats.
         */
        public List<String> spellings() {
            var all = new java.util.LinkedHashSet<String>();
            all.add(name);
            if (aliases != null) {
                aliases.stream().filter(a -> a != null && !a.isBlank()).forEach(all::add);
            }
            return List.copyOf(all);
        }
    }

    /**
     * One project the cover letter may cite.
     *
     * <p>The title exists twice and the period not at all as text, because both used to be
     * one field and both therefore ended up in one language. A German advert then got an
     * English title, which is the half of the letter a reader notices first. The period is
     * two {@link YearMonth}s and is rendered by {@code ProjectView}, so "since" and "seit"
     * are a property of the letter rather than of the profile. An absent {@code to} means
     * the work is still running.
     *
     * <p>The pitch fields are content, not repository language, and the title fields are
     * now the same: they go verbatim into a letter, in the language of the advert.
     */
    public record ReferenceProject(
            @NotBlank String id,
            String titleDe,
            String titleEn,
            YearMonth from,
            YearMonth to,
            String role,
            List<String> stack,
            String pitchDe,
            String pitchEn) {

        /**
         * A project with neither title renders as an empty line above its pitch, which is a
         * defect nobody sees until it is in front of a client. Fail at startup instead, where
         * every other malformed value in this file already fails.
         */
        @AssertTrue(message = "needs a title_de or a title_en") public boolean isTitled() {
            return !isBlank(titleDe) || !isBlank(titleEn);
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }

    public record Language(@NotBlank String name, String level) {}

    /**
     * A fixed PDF. There is no per-offer tailoring; the language of the ad picks the file.
     */
    public record CvVariant(
            @NotBlank String file, @JsonProperty("default") boolean isDefault) {}
}
