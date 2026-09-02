/**
 * What `shared/` needs from the layers above it, as tokens rather than imports.
 *
 * <p>`shared/` may import nothing from `@core`, `@layout` or `@features` — not even a type
 * — because a shared component that knows what an offer is has stopped being shared. The
 * eslint rule that enforces it names this file in its message, and this is that file: the
 * one place where a dependency pointing the wrong way is turned into one pointing the
 * right way.
 */
export { CHART_PALETTE, type ChartPalette } from './chart/chart-palette';
export { SCORE_THRESHOLDS, type ScoreThresholds } from './score/score-thresholds';
