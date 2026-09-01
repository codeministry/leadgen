import { type } from '@ngrx/signals';
import { eventGroup } from '@ngrx/signals/events';
import { RulesView } from '@core/model/rules-view';
import { SourceSummary } from '@core/model/source-summary';

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
