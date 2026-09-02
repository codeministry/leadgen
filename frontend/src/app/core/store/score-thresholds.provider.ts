import { Provider, Signal, computed, inject } from '@angular/core';
import { Dispatcher } from '@ngrx/signals/events';
import { SCORE_THRESHOLDS, ScoreThresholds } from '@shared/shared.ports';
import { configEvents } from './config.events';
import { ConfigStore } from './config.store';

/**
 * The configured thresholds, for the parts of `shared/` that need them.
 *
 * <p>Read from `GET /api/rules` through the store that already holds it, and asked for once
 * at startup rather than by whichever screen happens to render a score first — a ring on
 * the pipeline board must not depend on somebody having opened the rules screen.
 *
 * <p>The fallback is the shipped default, and it is only ever seen in the moment between
 * the app starting and the rules arriving. It is a number in TypeScript, which is the thing
 * this provider exists to remove — so it is here, once, and not in three components.
 */
export function provideScoreThresholds(): Provider {
  return {
    provide: SCORE_THRESHOLDS,
    useFactory: (): Signal<ScoreThresholds> => {
      const store = inject(ConfigStore);
      inject(Dispatcher).dispatch(configEvents.rulesOpened());
      return computed<ScoreThresholds>(() => {
        const thresholds = store.rules()?.thresholds;
        return {
          shortlistAt: thresholds?.autoShortlist ?? 70,
          reviewAt: thresholds?.review ?? 50,
        };
      });
    },
  };
}
