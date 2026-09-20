import {type} from '@ngrx/signals';
import {eventGroup} from '@ngrx/signals/events';
import {
  ApplicationEvent,
  ApplicationUpdate,
  ApplicationView,
  PipelineLane,
  TransitionMap,
} from '@core/model/application';

export const applicationEvents = eventGroup({
    source: 'Applications',
    events: {
        opened: type<void>(),
      loaded: type<{
        applications: readonly ApplicationView[];
        lanes: readonly PipelineLane[];
        transitions: TransitionMap;
      }>(),
        failed: type<string>(),
        /** The operator says this is where the application stands now. */
        changed: type<{ id: number; update: ApplicationUpdate }>(),
        updated: type<ApplicationView>(),
        /**
         * The id and not only the message: the board moves the card before the answer is
         * back, so a failure has to name the card whose row goes back where it was.
         */
        changeFailed: type<{ id: number; message: string }>(),
        historyRequested: type<number>(),
        historyLoaded: type<{ id: number; events: readonly ApplicationEvent[] }>(),
      /**
       * A package was asked for and the server is building it. The board is read again a
       * few times until the folder shows up, because the build is a background job and
       * nothing pushes its result.
       */
      packageAwaited: type<number>(),
      /**
       * The rows again, and nothing else. `loaded` carries the lanes and the transition
       * map with it, which are decisions that do not change between two polls.
       */
      boardRefreshed: type<readonly ApplicationView[]>(),
    },
});
