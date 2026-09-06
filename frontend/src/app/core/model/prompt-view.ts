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
    readonly system: string;
    /** The shape of the message an offer arrives in, not a real offer. */
    readonly user: string;
}
