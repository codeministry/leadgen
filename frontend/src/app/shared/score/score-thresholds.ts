import { InjectionToken, Signal } from '@angular/core';

/**
 * The two numbers that decide a score's band, as the configuration states them.
 *
 * <p>They used to be input defaults on `lg-score` — 70 and 50 — and no caller ever passed
 * anything else, so every score ring in the app banded off a constant while the rules
 * screen and the analytics histogram read the real values from the payload. Two screens
 * disagreeing about the same threshold is the failure; a third copy in TypeScript is the
 * cause.
 *
 * <p>A token rather than an import, because `shared/` may not reach into `@core` — the same
 * seam the chart palette uses.
 */
export interface ScoreThresholds {
  readonly shortlistAt: number;
  readonly reviewAt: number;
}

export const SCORE_THRESHOLDS = new InjectionToken<Signal<ScoreThresholds>>('lg.score.thresholds');
