import { type } from '@ngrx/signals';
import { eventGroup } from '@ngrx/signals/events';
import { RulesView } from '@core/model/rules-view';
import { SourceSummary } from '@core/model/source-summary';

/**
 * Two screens, one store, and therefore two `*Opened` events: the sources list and the rules
 * are loaded independently, and only the first of them is invalidated by a finished run.
 */
export const configEvents = eventGroup({
  source: 'Config',
  events: {
    sourcesOpened: type<void>(),
    sourcesLoaded: type<readonly SourceSummary[]>(),
    rulesOpened: type<void>(),
    rulesLoaded: type<RulesView>(),
    failed: type<string>(),
  },
});
