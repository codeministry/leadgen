/**
 * Mirrors `LlmBudgetView`: today's model calls against the day's ceiling.
 *
 * `limit` null means no ceiling is configured; zero means "ask nothing today". Opposite
 * answers, which is why the ceiling is nullable rather than defaulting to zero.
 */
export interface LlmBudgetView {
    readonly used: number;
    readonly limit: number | null;
}
