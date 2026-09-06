/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.content;

import de.codeministry.leadgen.config.model.PipelineConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The patterns that decide a block without asking anybody.
 *
 * <p><b>Not one of them is written in Java</b>, the same invariant `sources.yaml` and
 * `enrichment.extract` follow: a portal's furniture is a YAML block, not a release.
 *
 * <p>These are an <b>optimisation, not the mechanism</b>. They exist so a fresh clone with
 * no model configured still hides the obvious chrome, and so the common case costs nothing.
 * When a rule stops matching, nothing breaks and nothing is silently mislabelled — the block
 * simply falls through to the cache and then to the model, which is the whole reason the
 * tiers are arranged this way round.
 *
 * <p>They are matched against {@link BlockDigest#normalise}, not against the raw Markdown, so
 * a pattern is written against the words a person reads and not against flexmark's escaping.
 * The text is one line by then, which is what a pattern is written for.
 */
@Slf4j
public class ContentRules {

    private final List<Compiled> rules;

    public ContentRules(List<PipelineConfig.Content.Rule> configured) {
        List<Compiled> compiled = new ArrayList<>();
        if (configured != null) {
            for (PipelineConfig.Content.Rule rule : configured) {
                ContentKind kind = named(rule.kind());
                Pattern pattern = kind == null ? null : compile(rule);
                if (pattern != null) {
                    compiled.add(new Compiled(kind, pattern));
                }
            }
        }
        this.rules = List.copyOf(compiled);
    }

    /**
     * The first rule that matches, or nothing. First and not best: the order in the file is
     * the operator's own priority, and a "most specific wins" rule would need a definition of
     * specific that a YAML list does not have.
     */
    public Optional<ContentKind> kindOf(String normalised) {
        for (Compiled rule : rules) {
            if (rule.pattern().matcher(normalised).find()) {
                return Optional.of(rule.kind());
            }
        }
        return Optional.empty();
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /**
     * Loud and not fatal, exactly as {@code AdExtractor} does it: one broken pattern must not
     * stop the ones that work, and this stage is allowed to yield less than everything.
     */
    private static Pattern compile(PipelineConfig.Content.Rule rule) {
        try {
            return Pattern.compile(rule.matches(), Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            log.error("content.rules pattern for {} does not compile: {}", rule.kind(), e.getMessage());
            return null;
        }
    }

    /**
     * A kind nothing knows is logged and dropped rather than fatal, the same answer a
     * pattern that does not compile gets. A rule is an optimisation; a typo in one must cost
     * the optimisation and nothing else.
     */
    private static ContentKind named(String configured) {
        try {
            return ContentKind.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.error(
                "content.rules names kind '{}', which is not one of {}",
                configured,
                Arrays.toString(ContentKind.values()));
            return null;
        }
    }

    private record Compiled(ContentKind kind, Pattern pattern) {
    }
}
