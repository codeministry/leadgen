import {type} from '@ngrx/signals';
import {eventGroup} from '@ngrx/signals/events';

/**
 * What the sign-in itself has to say to the rest of the application.
 *
 * <p>One expiry event and not one per cause: a renewal the identity provider refused, a request the
 * API still answered 401 after a renewal and a sign-in that failed on the way back all end in the
 * same place — the session is over and the app signs in again — so the toast store maps one event
 * to one sentence and never learns which it was. `unsaved` says whether something on the page would
 * be lost, in which case the sign-in waits until it is gone or the person asks. `sessionRefused` is
 * the loop brake: a sign-in happened moments ago and the server still says no, so a second
 * redirect would only start a loop. `AuthService` dispatches at most one of the two per page; a
 * burst of failures is one event. `sessionAccepted` lifts the brake again: a request went through.
 * `issuerUnreachable` is the start finding no identity provider, and `signInFailed` a sign-in that went
 * nowhere: a code that came back and could not be exchanged, or a token lapsed on arrival, moments after
 * the last attempt, so a redirect would only bounce — or a sign-in that could not start at all.
 */
export const authEvents = eventGroup({
    source: 'Auth',
    events: {
        sessionExpired: type<{readonly unsaved: boolean}>(),
        sessionRefused: type<void>(),
        sessionAccepted: type<void>(),
        issuerUnreachable: type<void>(),
        signInFailed: type<void>(),
        /** A sign-in started a while ago and the page is still here: stopped, or failing quietly. */
        signInStalled: type<void>(),
        /** A toast's "Sign in now" or "Try again": the person asks for the sign-in themselves. */
        signInRequested: type<void>(),
    },
});
