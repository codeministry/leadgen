import {type} from '@ngrx/signals';
import {eventGroup} from '@ngrx/signals/events';
import {CoverLetter} from '@core/model/cover-letter';

/**
 * Every answer carries the offer id, so a reply that arrives after the reader moved on to
 * another offer can be told apart from the one on screen and dropped.
 */
export const coverLetterEvents = eventGroup({
    source: 'Cover letter',
    events: {
        /** The offer detail shows a packaged application; read its letter. */
        requested: type<number>(),
        /** `null` is the 404: no package yet, which is a state and not a failure. */
        loaded: type<{offerId: number; letter: CoverLetter | null}>(),
        loadFailed: type<{offerId: number; message: string}>(),
        saveRequested: type<{offerId: number; text: string}>(),
        saved: type<{offerId: number; letter: CoverLetter}>(),
        draftRequested: type<number>(),
        /** The toast layer reads this one: the new letter's author says how the draft went. */
        drafted: type<{offerId: number; letter: CoverLetter}>(),
        /** A refused save or draft. The letter on screen stays what the server last stored. */
        writeFailed: type<{offerId: number; message: string}>(),
    },
});
