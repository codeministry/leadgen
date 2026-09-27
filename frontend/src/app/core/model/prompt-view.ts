/**
 * What this configuration actually sends to a language model.
 *
 * `id` is a closed id and not a sentence, so the browser holds the label — the same rule a
 * filter stage and a content kind follow. `system` and `user` are the server's own prose and
 * stay English in both languages, like every score reason.
 */
export interface PromptView {
    readonly id: string;
    /** Which model answers, or null when none is configured. */
    readonly model: string | null;
    /**
     * The stage's own key, `llm.models.<stage>`, named by the server whether it answered or
     * fell back. Null when no model answers.
     */
    readonly ownKey: string | null;
    /**
     * The key that decided `model`: the stage's own, or `llm.models.scoring` when the stage's
     * own key is empty. Null when no model answers.
     */
    readonly modelKey: string | null;
    /** True only when the stage's own key is empty and the scoring model answers in its place. */
    readonly modelFallback: boolean;
    readonly system: string;
    /** The shape of the message an offer arrives in, not a real offer. */
    readonly user: string;
}
