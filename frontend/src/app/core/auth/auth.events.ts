import {type} from '@ngrx/signals';
import {eventGroup} from '@ngrx/signals/events';

/**
 * What the sign-in itself has to say to the rest of the application.
 *
 * <p>One expiry event and not one per cause: a renewal the identity provider refused, a request the
 * API answered 401 and a sign-in that failed on the way back all end in the same place — the
 * session is over and the app signs in again — so the toast store maps one event to one sentence
 * and never learns which it was. `unsaved` says whether something on the page would be lost, in
 * which case the sign-in waits for `signInRequested` instead of a timer. `sessionRefused` is the
 * loop brake: a sign-in for an expired session happened moments ago and the server still says no,
 * so a second redirect would only start a loop. `AuthService` dispatches at most one of the two
 * per page; a burst of failures is one event.
 */
export const authEvents = eventGroup({
    source: 'Auth',
    events: {
        sessionExpired: type<{readonly unsaved: boolean}>(),
        sessionRefused: type<void>(),
        /** The toast's "Sign in now", when unsaved work held the redirect. */
        signInRequested: type<void>(),
    },
});
