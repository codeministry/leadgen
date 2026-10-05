import {inject} from '@angular/core';
import {signalStore, withState} from '@ngrx/signals';
import {Events, on, withEventHandlers, withReducer} from '@ngrx/signals/events';
import {distinctUntilChanged, distinctUntilKeyChanged, filter, map, merge, mergeMap, switchMap, take, takeUntil, timer} from 'rxjs';
import {ApplicationStatus, statusLabel} from '@core/model/application';
import {LastRunView} from '@core/model/last-run';
import {refreshEvents} from '@core/refresh/refresh.events';
import {applicationEvents} from '@core/store/applications.events';
import {coverLetterEvents} from '@core/store/cover-letter.events';
import {ingestEvents} from '@core/store/ingest.events';
import {RUN_STATUS} from '@core/model/workflow';
import {manualEvents} from '@core/store/manual.events';
import {shortlistEvents} from '@core/store/shortlist.events';
import {updateEvents} from '@core/pwa/update.events';
import {authEvents} from '@core/auth/auth.events';
import {toastEvents} from './toast.events';
import {actionToast, TOAST_CAP, TOAST_LIFETIME_MS, Toast, toast} from './toast.model';
import {withAppDevtools} from '@core/store/devtools';

interface ToastState {
    /** Oldest first. The stack renders them in this order and the cap drops from the front. */
    toasts: readonly Toast[];
}

const initialState: ToastState = {toasts: []};

/**
 * The states that close an application against us. A move into one of them is "taken
 * away" and takes the warning tone; every other move, WON included, is forward and green.
 */
const CLOSED_AGAINST_US: ReadonlySet<ApplicationStatus> = new Set<ApplicationStatus>(['LOST', 'REJECTED', 'EXPIRED']);

/** The one key that may stand only once: a newer version replaces the offer of the older. */
const UPDATE_READY_KEY = 'toast.update.ready';

/**
 * The one place a domain event becomes a message.
 *
 * <p>It listens to the answer events of the other stores — what the server wrote, never
 * what was asked for — and raises one toast per answer. That is what lets the detail and
 * the card produce the same line for one archive without either of them knowing a toast
 * exists, and what makes "a refused move raises none" free: `updated` only fires when the
 * server said yes, and the failure events are simply not subscribed to.
 *
 * <p>The timer lives here rather than in the stack, so the lifetime is one rule in one
 * place and a spec can run it under fake timers without rendering anything.
 */
export const ToastStore = signalStore(
    {providedIn: 'root'},
    withState(initialState),
    withAppDevtools('toast'),
    withReducer(
        // The cap drops the oldest passing line: the newest is the one that just happened, and
        // the one the person is most likely looking for — unless every line before it stands and
        // waits for an answer, which outranks a line that would have left by itself anyway.
        on(toastEvents.raised, ({payload}, state) => ({
            toasts: capped([...state.toasts, payload]),
        })),
        on(toastEvents.dismissed, toastEvents.expired, ({payload}, state) => ({
            toasts: state.toasts.filter((standing) => standing.id !== payload),
        })),
    ),
    withEventHandlers((store) => {
        const events = inject(Events);

        /** Whichever of the two carries this id: the timer's own end, or the button. */
        const gone = (id: number) =>
            events
                .on(toastEvents.held, toastEvents.dismissed, toastEvents.expired)
                .pipe(filter(({payload}) => payload === id));

        return [
            /*
             * One timer per raise and one per release, each cancelled by a hold, a dismiss
             * or its own expiry. A release starts a fresh full lifetime rather than resuming
             * the remainder: the person just read it, and a line that vanishes the instant
             * the pointer leaves is the thing the hold exists to prevent.
             *
             * A `standing` toast gets no timer on either path: the offer stands until the person
             * takes it or closes it. An action alone is no exemption — the archive toast's
             * Restore leaves with the toast. The raise carries the toast; a release carries only
             * the id, and the toast it names has stood in the state since its raise.
             */
            events.on(toastEvents.raised, toastEvents.released).pipe(
                map(({payload}) =>
                    typeof payload === 'number' ? store.toasts().find((standing) => standing.id === payload) : payload,
                ),
                filter((standing): standing is Toast => standing !== undefined && standing.standing !== true),
                map(({id}) => id),
                mergeMap((id) =>
                    timer(TOAST_LIFETIME_MS).pipe(
                        takeUntil(gone(id)),
                        map(() => toastEvents.expired(id)),
                    ),
                ),
            ),

            // ── The mappings. One per answer event; the key names the catalog line. ──

            // Raised from the answer, so whichever screen wrote it — the swipe, the `a` key, the
            // detail's button — produces the same toast. The direction is read off the row the
            // server returned, not off the request.
            //
            // An archive offers Restore in place of Open: Open on an archived offer only leads to
            // a detail whose main action is the same restore, one tap further away. Restore is the
            // existing restore request, a second write the shortlist store handles under its own
            // rules. The answer to it is a restore and raises the green line with Open and no
            // reverse action; a symmetrical undo would invite ping-pong. The toast keeps the timer:
            // the permanent way back is the archive view.
            //
            // Not when the offer kept its package: then it was sent, a restore resets its
            // application to NEW, and every other restore path asks first (ISC-495). The question
            // is a dialog on the screen, and a toast never writes on its own, so this one keeps
            // Open and leaves the restore to the detail, which asks (review finding 3). Its
            // Restore is `inline`: a refusal stands in the row once the row is back on screen.
            events.on(shortlistEvents.archived).pipe(
                map(({payload}) =>
                    toastEvents.raised(
                        payload.offer.archivedAt === null
                            ? toast('success', 'toast.restored', {title: payload.offer.title}, `/shortlist/${payload.offer.id}`)
                            : payload.offer.packageDir
                            ? toast('warning', 'toast.archived', {title: payload.offer.title}, `/shortlist/${payload.offer.id}`)
                            : actionToast(
                                'warning',
                                'toast.archived',
                                {
                                    key: 'toast.restore',
                                    event: shortlistEvents.archiveRequested({id: payload.offer.id, archived: false, inline: true}),
                                },
                                {title: payload.offer.title},
                            ),
                    ),
                ),
            ),
            // The count the server wrote, never the count that was asked for: an id that
            // named no row was not archived, and the sentence must not say it was.
            events.on(shortlistEvents.bulkArchived).pipe(
                map(({payload}) =>
                    toastEvents.raised(toast('warning', 'toast.countArchived', {count: payload.archived})),
                ),
            ),
            // From `updated`, the row the server returned, and never from the optimistic
            // `changed`: the board moves the card before the answer is back, and a refused
            // move puts it back. A toast for that move would confirm what did not happen.
            // Amber for a move that closes the application against us, green for every other.
            events.on(applicationEvents.updated).pipe(
                map(({payload}) =>
                    toastEvents.raised(
                        toast(
                            CLOSED_AGAINST_US.has(payload.status) ? 'warning' : 'success',
                            'toast.statusChanged',
                            {title: payload.title, state: statusLabel(payload.status)},
                            `/pipeline/${payload.id}`,
                        ),
                    ),
                ),
            ),
            // The score the server stored. Null when nothing judged it — the pipeline runs
            // without a model — and that is a different sentence, not a missing number.
            events.on(shortlistEvents.rescored).pipe(
                map(({payload}) =>
                    toastEvents.raised(
                        payload.score.value === null
                            ? toast('info', 'toast.rescoredUnscored', {title: payload.offer.title}, `/shortlist/${payload.offer.id}`)
                            : toast(
                                'success',
                                'toast.rescored',
                                {title: payload.offer.title, score: payload.score.value},
                                `/shortlist/${payload.offer.id}`,
                            ),
                    ),
                ),
            ),
            // What the fetch left on the offer, not whether the request went through: a page
            // that refused once more still answers with the entry, and its note is the reason.
            // Green for an advert that arrived; info for one still missing, the sibling of a rescore
            // still unscored rather than of an offer closed against us. A request the server
            // turned away raises nothing here: that sentence stays by the button.
            events.on(shortlistEvents.fetched).pipe(
                map(({payload}) =>
                    toastEvents.raised(
                        payload.offer.fullText
                            ? toast('success', 'toast.fetched', {title: payload.offer.title}, `/shortlist/${payload.offer.id}`)
                            : toast(
                                'info',
                                'toast.fetchRefused',
                                {title: payload.offer.title, reason: payload.offer.enrichmentNote ?? ''},
                                `/shortlist/${payload.offer.id}`,
                            ),
                    ),
                ),
            ),
            /*
             * A run beginning, from the heartbeat's first sight of it and never from
             * `requested`: the heartbeat sees a click here and a CronJob elsewhere alike, and
             * `IngestStore` asks it at once on a click. Keyed on the run id through the
             * stream itself rather than through state, because the order in which a reducer
             * and a handler see one event is not something to depend on. The null between two
             * runs is what lets a new id through; the same id on every beat is one toast.
             *
             * "Open" goes to the run status on the workflow screen (operator, 2026-09-27): the
             * one place that shows where a pass stands while it runs, stage by stage. The
             * dashboard it used to open only has something to say once the run is over.
             */
            events.on(ingestEvents.currentLoaded).pipe(
                map(({payload}) => payload?.id ?? null),
                distinctUntilChanged(),
                filter((id): id is number => id !== null),
                map(() =>
                    toastEvents.raised(
                        toast('info', 'toast.runStarted', undefined, '/workflow', {stage: RUN_STATUS}),
                    ),
                ),
            ),
            /*
             * A run ending, from two paths that both fire for the operator's own run: the
             * report the request handed back, and the recorded last run that `run-ended`
             * reads back a moment later. Both carry `finishedAt`, so the second is the same
             * key as the first and is dropped. A last run read for any other reason — the
             * store's own startup ask, a tab coming back — names no ending and raises nothing:
             * only a `run-ended` opens the window, and only the next answer closes it.
             *
             * A run that stopped in a stage is only ever seen on the second path — its request
             * answered 500, so `finished` never fired — and says where it stopped rather than
             * reporting counts that end there. Amber: something was taken away, the run.
             */
            merge(
                events.on(ingestEvents.finished).pipe(
                    map(({payload}) => ({
                        key: payload.finishedAt,
                        written: payload.written,
                        shortlisted: payload.scored.shortlisted,
                        failedIn: null as string | null,
                    })),
                ),
                events.on(refreshEvents.requested).pipe(
                    filter(({payload}) => payload === 'run-ended'),
                    switchMap(() => events.on(ingestEvents.lastRunLoaded).pipe(take(1))),
                    map(({payload}) => payload),
                    filter((run): run is LastRunView => run !== null),
                    map((run) => ({
                        key: run.finishedAt,
                        written: run.written,
                        shortlisted: run.shortlisted,
                        failedIn: run.status === 'FAILED' ? (run.stages?.at(-1)?.stage ?? '?') : null,
                    })),
                ),
            ).pipe(
                distinctUntilKeyChanged('key'),
                map(({written, shortlisted, failedIn}) =>
                    toastEvents.raised(
                        failedIn === null
                            ? toast('info', 'toast.runFinished', {written, shortlisted}, '/dashboard')
                            : toast('warning', 'toast.runFailed', {stage: failedIn}, '/dashboard'),
                    ),
                ),
            ),
            // The inbox's own answer carries the outcome, so one event names both sentences.
            events.on(manualEvents.settled).pipe(
                map(({payload}) =>
                    toastEvents.raised(
                        payload.outcome === 'confirmed'
                            ? toast('success', 'toast.documentConfirmed', {name: payload.name})
                            : toast('warning', 'toast.documentRejected', {name: payload.name}),
                    ),
                ),
            ),
            // A saved letter, from the server's answer. A refused save or draft raises nothing
            // here: that sentence stays beside the letter's buttons.
            events.on(coverLetterEvents.saved).pipe(
                map(() => toastEvents.raised(toast('success', 'toast.letterSaved'))),
            ),
            // A redraft, told by the author the server stored: a `template` answer means the
            // model's draft was refused or there was none, which the operator should know before
            // sending it — info, the sibling of a rescore that is still unscored.
            events.on(coverLetterEvents.drafted).pipe(
                map(({payload}) =>
                    toastEvents.raised(
                        payload.letter.author === 'template'
                            ? toast('info', 'toast.letterDraftedTemplate')
                            : toast('success', 'toast.letterDrafted'),
                    ),
                ),
            ),
            // The session ended — a renewal refused or a bearer answered 401 — and the sign-in starts
            // by itself after a short notice. `AuthService` dispatches this once per page, so a
            // burst of failures is one line. Warning, the "taken away" tone: what was taken is the
            // session. No action, since nothing waits on the person; no link, since there is no
            // page for it; the normal timer, because the page is gone well before it runs out.
            // With unsaved work on the page the sign-in waits instead, so the line stands and carries
            // the sign-in as its action. It waits for the person or for the work to go, whichever is
            // first: `AuthService` leaves by itself once nothing is left to lose. Nothing can be saved
            // meanwhile, since every request needs the session that ended, and the line says so.
            events.on(authEvents.sessionExpired).pipe(
                map(({payload}) =>
                    toastEvents.raised(
                        payload.unsaved
                            ? {
                                  ...actionToast('warning', 'toast.sessionExpiredUnsaved', {
                                      key: 'toast.signInNow',
                                      event: authEvents.signInRequested(),
                                  }),
                                  standing: true,
                              }
                            : toast('warning', 'toast.sessionExpired'),
                    ),
                ),
            ),
            // The loop brake: signed in moments ago and the server still refuses. Nothing redirects
            // by itself, so the line stands and offers the sign-in for when the person wants to try.
            events.on(authEvents.sessionRefused).pipe(
                map(() =>
                    toastEvents.raised({
                        ...actionToast('warning', 'toast.sessionRefused', {key: 'toast.signInNow', event: authEvents.signInRequested()}),
                        standing: true,
                    }),
                ),
            ),
            // The brake lifted: a request went through after all. The refusal line is wrong now, and
            // its Sign in now would do nothing, so it goes the way the button would take it.
            events.on(authEvents.sessionAccepted).pipe(
                mergeMap(() =>
                    store
                        .toasts()
                        .filter((standing) => standing.key === 'toast.sessionRefused')
                        .map((standing) => toastEvents.dismissed(standing.id)),
                ),
            ),
            // A sign-in that went nowhere: a code that could not be exchanged, or a token lapsed on
            // arrival, moments after the last attempt, so another redirect would only bounce; or a
            // sign-in that could not start at all. The line stands and its action is the next attempt.
            events.on(authEvents.signInFailed).pipe(
                map(() =>
                    toastEvents.raised({
                        ...actionToast('warning', 'toast.signInFailed', {key: 'toast.tryAgain', event: authEvents.signInRequested()}),
                        standing: true,
                    }),
                ),
            ),
            // A sign-in started and the page is still here a while later: the navigation was stopped
            // or failed quietly. The page keeps waiting, so the line only offers the next attempt.
            events.on(authEvents.signInStalled).pipe(
                map(() =>
                    toastEvents.raised({
                        ...actionToast('warning', 'toast.signInStalled', {key: 'toast.tryAgain', event: authEvents.signInRequested()}),
                        standing: true,
                    }),
                ),
            ),
            // The identity provider did not answer at the start. Nothing loads without it, so the
            // line stands and its action asks the issuer again — and signs in once it answers.
            events.on(authEvents.issuerUnreachable).pipe(
                map(() =>
                    toastEvents.raised({
                        ...actionToast('warning', 'toast.issuerUnreachable', {key: 'toast.tryAgain', event: authEvents.signInRequested()}),
                        standing: true,
                    }),
                ),
            ),
            // A new version the worker holds, once per hash — the update store keys that. The
            // one `standing` toast: the reload is the person's call and it stands until they
            // take it or close it. Info, because nobody in this browser asked for a deploy; and no
            // link, because there is no page for it. Never raised while the worker is disabled,
            // since the event then never fires. A standing update toast goes first: it describes
            // a version the worker no longer holds, and two identical offers is one too many.
            events.on(updateEvents.versionReady).pipe(
                mergeMap(() => [
                    ...store
                        .toasts()
                        .filter((standing) => standing.key === UPDATE_READY_KEY)
                        .map((standing) => toastEvents.dismissed(standing.id)),
                    toastEvents.raised({
                        ...actionToast('info', UPDATE_READY_KEY, {
                            key: 'toast.update.reload',
                            event: updateEvents.activate(),
                        }),
                        standing: true,
                    }),
                ]),
            ),
        ];
    }),
);

/**
 * The toasts that fit under `TOAST_CAP`. The oldest passing line leaves first — the newest one too,
 * when every line before it stands — and a standing one only when nothing but standing lines is left,
 * because a standing line is one the person still has to answer — the sign-in a held redirect waits
 * for, the reload a new version waits for — and a burst of archive toasts must not take that answer away.
 */
function capped(toasts: readonly Toast[]): Toast[] {
    const kept = [...toasts];
    while (kept.length > TOAST_CAP) {
        const passing = kept.findIndex((candidate) => !candidate.standing);
        kept.splice(passing === -1 ? 0 : passing, 1);
    }
    return kept;
}
