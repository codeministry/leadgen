/**
 * The pipeline as the rules screen shows it. Mirrors `de.codeministry.leadgen.workflow.WorkflowView`.
 *
 * Every field the records carry is sent, nulls included, so a nullable field is typed
 * `X | null` and never optional: an absent key and a null one are different answers, and
 * only the second is what the server says.
 *
 * Closed values travel as lower-case strings rather than as unions the browser would have to
 * keep in step: the server owns the lists, and the browser holds only the labels, under
 * `rules.phase.*`, `rules.stage.*` and `rules.cost.*`.
 */

/** One configuration key as it stands right now, already masked when it names a secret. */
export interface WorkflowSetting {
    /** The dotted leaf path, with `[]` for a sequence of mappings (`content.rules[].regex`). */
    readonly key: string;
    readonly value: string;
    /** The file that won for this key, e.g. `matching-rules.yaml`. */
    readonly file: string;
}

/** One hard filter on the FILTER stage. */
export interface WorkflowKnockout {
    /** The `FilterStage` it is, lower-case. */
    readonly id: string;
    /** The server's prose, English on every screen. */
    readonly description: string;
    /** The keys the filter reads for it; among the stage's `settings` only when the file declares them. */
    readonly keys: readonly string[];
}

/** `ingest` for one entry per enabled source, `stage` for a step every run passes once. */
export type WorkflowStageKind = 'ingest' | 'stage';

/** One step of a run. */
export interface WorkflowStage {
    /**
     * The name the run times it under — `INGEST <sourceId>` for an ingest entry, `DEDUPE` …
     * `DIGEST` otherwise — so the last run's timings join on it without a second mapping.
     */
    readonly id: string;
    readonly kind: WorkflowStageKind;
    /** The source an ingest entry reads; null on every other stage. */
    readonly sourceId: string | null;
    /** One sentence on what the stage does to an offer. The server's prose, English. */
    readonly description: string;
    /** Any of `free`, `model`, `network`, `file`; more than one when a stage does both. */
    readonly costClasses: readonly string[];
    /** The `PromptView` id of the prompt it sends, or null when it sends none. */
    readonly promptId: string | null;
    readonly settings: readonly WorkflowSetting[];
    /** The hard filters in `FilterStage` order on the stage that applies them; null elsewhere. */
    readonly knockouts: readonly WorkflowKnockout[] | null;
}

/** One phase of a run: `read`, `sort`, `understand`, `judge` or `hand`. */
export interface WorkflowPhase {
    readonly id: string;
    /** In the order a run executes them. The order is the meaning, and nothing re-sorts it. */
    readonly stages: readonly WorkflowStage[];
}

export interface WorkflowView {
    /** The five phases, in run order. */
    readonly phases: readonly WorkflowPhase[];
    /** Every key present in a file that no stage reads. Shown, never dropped. */
    readonly unread: readonly WorkflowSetting[];
}
