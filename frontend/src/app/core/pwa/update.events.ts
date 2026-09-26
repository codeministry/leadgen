import {type} from '@ngrx/signals';
import {eventGroup} from '@ngrx/signals/events';

/**
 * The worker's side of a deploy, as seen from this tab.
 *
 * <p>`versionReady` is the store's own event, raised once per version hash from the
 * worker's `versionUpdates` stream; the toast store maps it to the one toast that carries an
 * action. `activate` is that action — the toast's button dispatches it, and nothing else
 * does, because activating is the person's call and never a run's.
 */
export const updateEvents = eventGroup({
    source: 'Update',
    events: {
        /** A new version is downloaded and waiting; the payload is its hash. */
        versionReady: type<string>(),
        /** The reload button on the toast. */
        activate: type<void>(),
    },
});
