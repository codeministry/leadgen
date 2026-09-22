/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import de.codeministry.leadgen.config.model.MatchingRules;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.config.model.SkillProfile;
import de.codeministry.leadgen.config.model.SourcesConfig;
import java.time.Instant;

/**
 * The four files as one immutable unit. They are read together and swapped
 * together: a reload that applied to one of them and not the others would leave the
 * pipeline running against a picture that never existed on disk.
 *
 * <p>The profile joined them when the hard filter started needing it. "Does this offer
 * name a skill I actually have" cannot be answered from the rules, which describe the
 * criteria and not the person.
 */
public record ConfigSnapshot(
        PipelineConfig application,
        MatchingRules rules,
        SourcesConfig sources,
        SkillProfile profile,
        Instant loadedAt) {}
