import {type} from '@ngrx/signals';
import {eventGroup} from '@ngrx/signals/events';
import {IngestReport} from '@core/api/ingest.api';
import {CurrentRunView} from '@core/model/current-run';
import {LastRunView} from '@core/model/last-run';

export const ingestEvents = eventGroup({
    source: 'Ingest',
    events: {
        requested: type<void>(),
        finished: type<IngestReport>(),
        failed: type<string>(),
        /**
         * What ran before this browser was opened. Its own trio and not part of `finished`:
         * a report and a recorded run are different things, and the screen says which one it
         * is showing.
         */
        lastRunRequested: type<void>(),
      /**
       * The heartbeat that asks whether a pass is going on. Fired on a timer rather than by
       * a screen: a run started by the nightly CronJob, by another tab or by a second
       * machine is invisible here otherwise, and that invisibility is what this answers.
       */
      currentRequested: type<void>(),
      /** A pass in flight, or null when none is. */
      currentLoaded: type<CurrentRunView | null>(),
        /** Null when nothing has ever run — the server's 204, not an error. */
        lastRunLoaded: type<LastRunView | null>(),
        lastRunFailed: type<string>(),
    },
});
