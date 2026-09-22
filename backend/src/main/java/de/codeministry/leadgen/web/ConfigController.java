/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import de.codeministry.leadgen.config.*;
import de.codeministry.leadgen.ingest.extract.LlmExtractors;
import de.codeministry.leadgen.score.Judges;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * The configuration as the screens read it. Read-only, and deliberately so: the four YAML
 * files are the source of truth and they are hot-reloaded, so editing them through an
 * endpoint would mean two ways to change the same thing disagreeing about which won.
 */
@RestController
@RequestMapping("/api/v1")
class ConfigController {

    private final SourceQueryService sources;
    private final SourceDetailService details;
    private final ConfigRegistry config;
    private final Judges judges;

    ConfigController(SourceQueryService sources, SourceDetailService details, ConfigRegistry config, Judges judges) {
        this.sources = sources;
        this.details = details;
        this.config = config;
        this.judges = judges;
    }

    /**
     * The file that defines the sources, and the sources.
     *
     * <p>An envelope rather than a bare array since the layer left the row: it is one probe for
     * the whole file, and a badge repeating it per source claimed to know something that cannot
     * vary between two rows. <b>A breaking change to a published endpoint</b>, named as one in
     * the changelog rather than slipped in.
     */
    @GetMapping("/sources")
    SourcesView sources() {
        return sources.summaries();
    }

    /**
     * One source, opened: the block of {@code sources.yaml} that defines it, the connection it
     * names, and the runs it has had.
     *
     * <p>Still read-only, like everything else on this controller. What is new is that it
     * serves <b>file text</b>, so two rules hold that the rest of the class does not need. The
     * text is the file's own bytes with {@code YamlMask} over them, never the bound snapshot,
     * which has every {@code ${…}} already resolved. And the id <b>selects</b>: it is looked up
     * in the snapshot's list of sources and answers 404 when it names nothing. It never reaches
     * a path, because a request parameter that does is the shape of every directory traversal.
     *
     * @param runs how many of the source's runs to return, newest first. Clamped rather than
     *             validated: an unreasonable number is a request nobody meant, not one worth
     *             refusing, and the panel says how many it is showing either way.
     */
    @GetMapping("/sources/{id}")
    SourceDetail source(@PathVariable String id, @RequestParam(required = false, defaultValue = "0") int runs) {
        return details.detail(id, runs).orElseThrow(() -> new NoSuchSource(id));
    }

    /**
     * 404 and not an empty body: an id that names no source is a link somebody typed or a
     * source that has been taken out of the file since the page was loaded, and both deserve
     * to be told apart from a source with nothing to show.
     */
    @ResponseStatus(HttpStatus.NOT_FOUND)
    static class NoSuchSource extends RuntimeException {
        NoSuchSource(String id) {
            super("no source named '%s' is configured".formatted(id));
        }
    }

    @GetMapping("/rules")
    RulesView rules() {
        return RulesView.of(config.snapshot().rules());
    }

    /**
     * What this configuration actually sends to a language model.
     *
     * <p>Beside `/rules` rather than on its own path: the Rules screen answers "why did this
     * offer score what it scored", and until now it could answer only for the deterministic
     * half. Rendered rather than templated — see {@link PromptView}.
     *
     * <p>No key and no base URL is in it, and none is needed to render it: which model would
     * answer is shown, whether or not one currently can be.
     */
    @GetMapping("/prompts")
    List<PromptView> prompts() {
        var snapshot = config.snapshot();
        var choices = judges.choices();
        var llm = snapshot.application().llm();
        return PromptView.all(
                snapshot.rules(),
                snapshot.profile(),
                choices.isEmpty() ? null : choices.getFirst(),
                // The stage's own choice, asked rather than reproduced: a copy of it here
                // would name one model on the screen while the run used the other.
                LlmExtractors.modelFor(llm == null ? null : llm.models()));
    }

    /**
     * What the select beside the run button offers.
     *
     * <p>Read from {@code Judges} rather than from the snapshot directly, so the endpoint
     * and the allowlist that refuses a request cannot disagree: one list, one place.
     */
    @GetMapping("/scoring-models")
    ScoringModels scoringModels() {
        return ScoringModels.of(judges.choices());
    }
}
