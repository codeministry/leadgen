/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.tools;

import de.codeministry.leadgen.config.model.SkillProfile;
import java.util.List;

/**
 * The effective skill profile, as the profile tool hands it to the model.
 *
 * <p><b>The identity is cut to the roles and the seniority.</b> The name, the brand, the home
 * base and the freelance dates are personal data, and no model prompt in this tool receives them
 * today: the writer gets the skills and the projects, the judge the roles, the seniority and the
 * base; the name and the brand are printed under the letter by the template, after the model. The
 * base is left out here too, because a chat answer has no use for where the operator lives. The
 * CV variants are file paths and are left out as configuration.
 *
 * @param roles             the roles the operator offers
 * @param seniority         the stated seniority
 * @param core              core skills, with weights and the spellings adverts use
 * @param strong            strong skills
 * @param peripheral        peripheral skills
 * @param industries        industry names
 * @param interestTopics    topics that lift a score, by name
 * @param disinterestTopics topics that sink a score, by name
 * @param referenceProjects the projects a letter may cite, with their pitches
 * @param languages         spoken languages and levels
 */
public record ProfileResult(
        List<String> roles,
        String seniority,
        List<SkillProfile.Skill> core,
        List<SkillProfile.Skill> strong,
        List<SkillProfile.Skill> peripheral,
        List<String> industries,
        List<String> interestTopics,
        List<String> disinterestTopics,
        List<SkillProfile.ReferenceProject> referenceProjects,
        List<SkillProfile.Language> languages) {}
