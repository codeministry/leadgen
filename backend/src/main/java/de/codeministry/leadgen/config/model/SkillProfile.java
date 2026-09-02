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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
 * <p>The `pitch_de` fields are content, not repository language: they end up verbatim in
 * German cover letters.
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
        Map<String, @Valid CvVariant> cvVariants) {

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
     *     these as well as on the name, which is why "Spring", "Spring Data" and
     *     "Springboot" all count as Spring Boot without three entries.
     * @param since the year it was first used in earnest. Not read by the filter; scoring
     *     turns it into depth.
     */
    public record Skill(@NotBlank String skill, @Min(1) @Max(10) int weight, Integer since, List<String> aliases) {}

    public record Industry(@NotBlank String name, @Min(1) @Max(10) int weight, String note) {}

    public record ReferenceProject(
            @NotBlank String id,
            String title,
            String period,
            String role,
            List<String> stack,
            String pitchDe,
            String pitchEn) {}

    public record Language(@NotBlank String name, String level) {}

    /** A fixed PDF. There is no per-offer tailoring; the language of the ad picks the file. */
    public record CvVariant(@NotBlank String file, @JsonProperty("default") boolean isDefault) {}
}
