/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.tools;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.SkillProfile;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * The skill profile in force, as a tool the chat model can call.
 *
 * <p>Read from {@link ConfigRegistry#snapshot()}, which is the profile the scorer and the letter
 * writer use: the shipped default overridden by the file in the configuration directory when
 * there is one, reloaded with it. Reading a YAML file here would be a third notion of "the
 * profile", and after a reload it would be the wrong one.
 *
 * <p>It never returns the operator's name, brand, home base or freelance dates, nor the CV
 * files' paths; what it does return, and why that subset, is on {@link ProfileResult}.
 */
@Component
@RequiredArgsConstructor
public class ProfileTool {

    private final ConfigRegistry config;

    @Tool(
            name = "profile",
            description = "Returns the operator's skill profile in force: roles, seniority, skills by tier with"
                    + " weights, industries, the topics that lift or sink a score, the reference projects a"
                    + " cover letter may cite, and languages.")
    public ProfileResult profile() {
        SkillProfile profile = config.snapshot().profile();
        var identity = profile.identity();
        return new ProfileResult(
                identity == null || identity.roles() == null ? List.of() : identity.roles(),
                identity == null ? null : identity.seniority(),
                orEmpty(profile.core()),
                orEmpty(profile.strong()),
                orEmpty(profile.peripheral()),
                names(profile.industries(), SkillProfile.Industry::name),
                names(profile.interestTopicsOrEmpty(), SkillProfile.Topic::name),
                names(profile.disinterestTopicsOrEmpty(), SkillProfile.Topic::name),
                orEmpty(profile.referenceProjects()),
                orEmpty(profile.languages()));
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static <T> List<String> names(List<T> list, Function<T, String> name) {
        return orEmpty(list).stream().map(name).toList();
    }
}
