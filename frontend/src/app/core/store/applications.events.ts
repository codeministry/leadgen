import {type} from '@ngrx/signals';
import {eventGroup} from '@ngrx/signals/events';
import {ApplicationEvent, ApplicationUpdate, ApplicationView, PipelineLane,} from '@core/model/application';

export const applicationEvents = eventGroup({
    source: 'Applications',
    events: {
        opened: type<void>(),
        loaded: type<{ applications: readonly ApplicationView[]; lanes: readonly PipelineLane[] }>(),
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
    },
});
