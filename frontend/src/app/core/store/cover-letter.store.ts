import {HttpErrorResponse} from '@angular/common/http';
import {inject} from '@angular/core';
import {signalStore, withState} from '@ngrx/signals';
import {Events, on, withEventHandlers, withReducer} from '@ngrx/signals/events';
import {catchError, concatMap, exhaustMap, map, of, switchMap} from 'rxjs';
import {CoverLetterApi} from '@core/api/cover-letter.api';
import {serverMessage} from '@core/api/server-message';
import {CoverLetter} from '@core/model/cover-letter';
import {coverLetterEvents} from './cover-letter.events';
import {withAppDevtools} from '@core/store/devtools';

interface CoverLetterState {
    /** The offer whose letter this is. One at a time: the detail shows one offer. */
    offerId: number | null;
    letter: CoverLetter | null;
    loading: boolean;
    saving: boolean;
    drafting: boolean;
    /** A catalog key, or the server's own sentence. */
    error: string | null;
}

const initialState: CoverLetterState = {
    offerId: null,
    letter: null,
    loading: false,
    saving: false,
    drafting: false,
    error: null,
};

function status(error: unknown): number | null {
    return error instanceof HttpErrorResponse ? error.status : null;
}

/**
 * Why a save or a draft came back refused, as a sentence that says what to do about it.
 *
 * The two refusals the endpoint names are keyed here rather than taken from the body: 409 is
 * the application having been sent, where the letter is what went out and stays as it is, and
 * 429 is the model budget, where waiting is the answer. Anything else is the server's own
 * sentence, or the fallback when it said nothing.
 */
function refusal(error: unknown, fallback: string): string {
    switch (status(error)) {
        case 409:
            return 'error.letterSent';
        case 429:
            return 'error.letterBudget';
        default:
            return serverMessage(error as {error?: unknown}, fallback);
    }
}

/**
 * The letter of the offer on screen: read, saved by hand, or drafted again.
 *
 * The server's answer always replaces the letter, never what was typed — so after a save the
 * textarea shows what is actually in `cover_letter.txt`, and after a refused one it shows what
 * still is.
 */
export const CoverLetterStore = signalStore(
    {providedIn: 'root'},
    withState(initialState),
    withAppDevtools('coverLetter'),
    withReducer(
        on(coverLetterEvents.requested, ({payload}, state) => ({
            offerId: payload,
            // The same offer read again keeps its letter on screen until the answer is back;
            // another offer's letter must not stand under this one's title for a round trip.
            letter: state.offerId === payload ? state.letter : null,
            loading: true,
            saving: false,
            drafting: false,
            error: null,
        })),
        on(coverLetterEvents.loaded, ({payload}, state) =>
            payload.offerId === state.offerId ? {letter: payload.letter, loading: false} : {},
        ),
        on(coverLetterEvents.loadFailed, ({payload}, state) =>
            payload.offerId === state.offerId ? {error: payload.message, loading: false} : {},
        ),
        on(coverLetterEvents.saveRequested, () => ({saving: true, error: null})),
        on(coverLetterEvents.draftRequested, () => ({drafting: true, error: null})),
        on(coverLetterEvents.saved, coverLetterEvents.drafted, ({payload}, state) =>
            payload.offerId === state.offerId
                ? {letter: payload.letter, saving: false, drafting: false}
                : {saving: false, drafting: false},
        ),
        on(coverLetterEvents.writeFailed, ({payload}, state) =>
            payload.offerId === state.offerId
                ? {error: payload.message, saving: false, drafting: false}
                : {saving: false, drafting: false},
        ),
    ),
    withEventHandlers(() => {
        const events = inject(Events);
        const api = inject(CoverLetterApi);

        return [
            // `switchMap`: moving to the next offer cancels the read of the last one.
            events.on(coverLetterEvents.requested).pipe(
                switchMap(({payload: offerId}) =>
                    api.read(offerId).pipe(
                        map((letter) => coverLetterEvents.loaded({offerId, letter})),
                        catchError((error: unknown) =>
                            of(
                                status(error) === 404
                                    ? coverLetterEvents.loaded({offerId, letter: null})
                                    : coverLetterEvents.loadFailed({
                                        offerId,
                                        message: serverMessage(error as {error?: unknown}, 'error.letterLoad'),
                                    }),
                            ),
                        ),
                    ),
                ),
            ),
            // Serialised: two saves in quick succession land in the order they were made.
            events.on(coverLetterEvents.saveRequested).pipe(
                concatMap(({payload}) =>
                    api.save(payload.offerId, payload.text).pipe(
                        map((letter) => coverLetterEvents.saved({offerId: payload.offerId, letter})),
                        catchError((error: unknown) =>
                            of(
                                coverLetterEvents.writeFailed({
                                    offerId: payload.offerId,
                                    message: refusal(error, 'error.letterSave'),
                                }),
                            ),
                        ),
                    ),
                ),
            ),
            // `exhaustMap`: a draft costs a model call, so a second click while one is running
            // is ignored rather than paid for twice.
            events.on(coverLetterEvents.draftRequested).pipe(
                exhaustMap(({payload: offerId}) =>
                    api.draft(offerId).pipe(
                        map((letter) => coverLetterEvents.drafted({offerId, letter})),
                        catchError((error: unknown) =>
                            of(coverLetterEvents.writeFailed({offerId, message: refusal(error, 'error.letterDraft')})),
                        ),
                    ),
                ),
            ),
        ];
    }),
);
