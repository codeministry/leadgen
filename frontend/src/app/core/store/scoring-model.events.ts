import { type } from '@ngrx/signals';
import { eventGroup } from '@ngrx/signals/events';
import { ScoringModels } from '@core/model/scoring-models';

export const scoringModelEvents = eventGroup({
  source: 'Scoring Model',
  events: {
    opened: type<void>(),
    loaded: type<ScoringModels>(),
    failed: type<string>(),
    /** What localStorage held, before the list is known. Null means the server's default. */
    restored: type<string | null>(),
    chosen: type<string>(),
  },
});
