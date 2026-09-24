/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.workflow;

import java.util.List;

/**
 * The pipeline as the rules screen shows it: phases, the stages inside them in the order a run
 * executes them, and every configuration key filed under the stage that reads it.
 *
 * <p>Closed values travel as lower-case strings rather than as enums, the same way
 * {@code PromptView.id} does: the browser holds the label, and the wire value is what both halves
 * agree on.
 *
 * @param phases the five phases of {@code docs/BACKEND-FLOWS.md} §1, in run order
 * @param unread every key present in a file that no stage reads. Shown, never dropped: a key the
 *               catalog does not know is exactly what the operator needs to see
 */
public record WorkflowView(List<Phase> phases, List<Setting> unread) {

    /** {@link Stage#kind()} of one entry per enabled source. */
    public static final String KIND_INGEST = "ingest";

    /** {@link Stage#kind()} of a stage every run passes through once. */
    public static final String KIND_STAGE = "stage";

    /** Deterministic, runs locally, costs nothing. */
    public static final String COST_FREE = "free";

    /** Asks a language model. */
    public static final String COST_MODEL = "model";

    /** Leaves the machine: a mailbox, a feed, an advert page. */
    public static final String COST_NETWORK = "network";

    /** Writes to disk. */
    public static final String COST_FILE = "file";

    public WorkflowView {
        phases = List.copyOf(phases);
        unread = List.copyOf(unread);
    }

    /**
     * One phase of a run.
     *
     * @param id a closed id; the browser holds the label
     */
    public record Phase(String id, List<Stage> stages) {

        public Phase {
            stages = List.copyOf(stages);
        }
    }

    /**
     * One step of a run.
     *
     * @param id          the name {@code IngestService} times it under, so the screen can join a
     *                    stage to the last run's timings without a second mapping
     * @param kind        {@link #KIND_INGEST} or {@link #KIND_STAGE}
     * @param sourceId    the source an ingest entry reads, null on every other stage
     * @param description one sentence on what the stage does to an offer
     * @param costClasses what running it costs, any of the {@code COST_*} values; more than one
     *                    when a stage both asks a model and fetches a page
     * @param promptId    the {@code PromptView} id of the prompt it sends, or null when it sends
     *                    none
     * @param settings    the keys it reads, each with its value and the file it came from
     * @param knockouts   the hard filters, in {@code FilterStage} order, on the stage that applies
     *                    them; null on every other stage
     */
    public record Stage(
            String id,
            String kind,
            String sourceId,
            String description,
            List<String> costClasses,
            String promptId,
            List<Setting> settings,
            List<Knockout> knockouts) {

        public Stage {
            costClasses = List.copyOf(costClasses);
            settings = List.copyOf(settings);
            knockouts = knockouts == null ? null : List.copyOf(knockouts);
        }

        /**
         * The entry for one enabled source. Its id is the name the run times it under,
         * {@code "INGEST " + sourceId}.
         */
        public static Stage ingest(
                String sourceId,
                String description,
                List<String> costClasses,
                String promptId,
                List<Setting> settings) {
            return new Stage(
                    "INGEST " + sourceId, KIND_INGEST, sourceId, description, costClasses, promptId, settings, null);
        }

        /** A stage every run passes once. */
        public static Stage stage(
                String id,
                String description,
                List<String> costClasses,
                String promptId,
                List<Setting> settings,
                List<Knockout> knockouts) {
            return new Stage(id, KIND_STAGE, null, description, costClasses, promptId, settings, knockouts);
        }
    }

    /**
     * One configuration key as it stands right now.
     *
     * @param key   the dotted leaf path, with {@code []} for a sequence of mappings
     *              ({@code content.rules[].regex}); a sequence of scalars is one leaf
     * @param value the rendered value, already masked when the key names a secret
     * @param file  the file name that won for this key, e.g. {@code matching-rules.yaml}
     */
    public record Setting(String key, String value, String file) {}

    /**
     * One hard filter on the FILTER stage.
     *
     * @param id          the {@code FilterStage} it is, lower-case
     * @param description one sentence on what it rejects
     * @param keys        the keys the filter reads for it, from the catalog; each is among the stage's
     *                    {@code settings} when the file declares it, and named even when it does not
     */
    public record Knockout(String id, String description, List<String> keys) {

        public Knockout {
            keys = List.copyOf(keys);
        }
    }
}
