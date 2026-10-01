import {type} from '@ngrx/signals';
import {eventGroup} from '@ngrx/signals/events';

/**
 * What the sign-in itself has to say to the rest of the application.
 *
 * <p>One event and not one per cause: a renewal the identity provider refused and a request the
 * API answered 401 end in the same place — the session is over and the app signs in again — so
 * the toast store maps one event to one sentence and never learns which of the two it was.
 * `AuthService` dispatches it at most once per page; a burst of failures is one event.
 */
export const authEvents = eventGroup({
    source: 'Auth',
    events: {
        sessionExpired: type<void>(),
    },
});
