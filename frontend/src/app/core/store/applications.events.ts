import { type } from '@ngrx/signals';
import { eventGroup } from '@ngrx/signals/events';
import {
  ApplicationEvent,
  ApplicationUpdate,
  ApplicationView,
  PipelineLane,
} from '@core/model/application';

export const applicationEvents = eventGroup({
  source: 'Applications',
  events: {
    opened: type<void>(),
    loaded: type<{ applications: readonly ApplicationView[]; lanes: readonly PipelineLane[] }>(),
    failed: type<string>(),
    /** The operator says this is where the application stands now. */
    changed: type<{ id: number; update: ApplicationUpdate }>(),
    updated: type<ApplicationView>(),
    changeFailed: type<string>(),
    historyRequested: type<number>(),
    historyLoaded: type<{ id: number; events: readonly ApplicationEvent[] }>(),
  },
});
