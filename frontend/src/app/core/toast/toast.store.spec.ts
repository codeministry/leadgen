import {TestBed} from '@angular/core/testing';
import {injectDispatch} from '@ngrx/signals/events';
import {IngestReport} from '@core/api/ingest.api';
import {ApplicationView} from '@core/model/application';
import {CurrentRunView} from '@core/model/current-run';
import {LastRunView} from '@core/model/last-run';
import {refreshEvents} from '@core/refresh/refresh.events';
import {ShortlistEntry} from '@core/model/shortlist-entry';
import {applicationEvents} from '@core/store/applications.events';
import {ingestEvents} from '@core/store/ingest.events';
import {manualEvents} from '@core/store/manual.events';
import {shortlistEvents} from '@core/store/shortlist.events';
import {toastEvents} from './toast.events';
import {TOAST_CAP, TOAST_LIFETIME_MS, toast} from './toast.model';
import {ToastStore} from './toast.store';

/**
 * Only what the toast reads off an archive answer. The rest of `ShortlistEntry` is
 * another store's business.
 */
function archiveAnswer(id: number, title: string, archivedAt: string | null): ShortlistEntry {
    return {offer: {id, title, archivedAt}} as unknown as ShortlistEntry;
}

/** Only what the toast reads off a rescore answer. */
function scoreAnswer(id: number, title: string, value: number | null): ShortlistEntry {
    return {offer: {id, title, archivedAt: null}, score: {value}} as unknown as ShortlistEntry;
}

function application(overrides: Partial<ApplicationView> = {}): ApplicationView {
    return {
        id: 3,
        offerId: 7,
        status: 'SHORTLISTED',
        title: 'Senior Java Entwickler (m/w/d)',
        agency: null,
        portal: null,
        url: null,
        scoreValue: 88,
        rateEur: null,
        packageDir: null,
        sentOn: null,
        followUpOn: null,
        followUpDue: false,
        outcome: null,
        note: null,
        updatedAt: '2026-09-23T10:00:00Z',
        ...overrides,
    };
}

describe('ToastStore', () => {
    let store: InstanceType<typeof ToastStore>;
    let dispatch: ReturnType<typeof injectDispatch<typeof toastEvents>>;
    let shortlist: ReturnType<typeof injectDispatch<typeof shortlistEvents>>;
    let applications: ReturnType<typeof injectDispatch<typeof applicationEvents>>;
    let manual: ReturnType<typeof injectDispatch<typeof manualEvents>>;
    let ingest: ReturnType<typeof injectDispatch<typeof ingestEvents>>;
    let refresh: ReturnType<typeof injectDispatch<typeof refreshEvents>>;

    beforeEach(() => {
        // Only this store is created. The other stores are never injected, so their events
        // are dispatched into a room where the toast store is the only listener.
        store = TestBed.inject(ToastStore);
        dispatch = TestBed.runInInjectionContext(() => injectDispatch(toastEvents));
        shortlist = TestBed.runInInjectionContext(() => injectDispatch(shortlistEvents));
        applications = TestBed.runInInjectionContext(() => injectDispatch(applicationEvents));
        manual = TestBed.runInInjectionContext(() => injectDispatch(manualEvents));
        ingest = TestBed.runInInjectionContext(() => injectDispatch(ingestEvents));
        refresh = TestBed.runInInjectionContext(() => injectDispatch(refreshEvents));
    });

    describe('a bulk archive', () => {
        it('names the count the server wrote, never the count asked for', () => {
            shortlist.bulkArchived({ids: [1, 2, 3, 4, 5], archived: 3, unscored: 1});

            expect(store.toasts().length).toBe(1);
            expect(store.toasts()[0]).toMatchObject({key: 'toast.countArchived', params: {count: 3}});
        });
    });

    describe('a status change', () => {
        it('is confirmed from the row the server returned, naming the state and linking to the card', () => {
            applications.changed({id: 3, update: {status: 'SENT'}});
            expect(store.toasts().length).toBe(0);

            applications.updated(application({status: 'SENT'}));
            expect(store.toasts().length).toBe(1);
            expect(store.toasts()[0]).toMatchObject({
                key: 'toast.statusChanged',
                params: {title: 'Senior Java Entwickler (m/w/d)', state: 'Sent'},
                link: '/pipeline/3',
            });
        });

        it('raises nothing for a move the server refused', () => {
            applications.changed({id: 3, update: {status: 'SENT'}});
            applications.changeFailed({id: 3, message: 'error.statusBlocked'});

            expect(store.toasts().length).toBe(0);
        });
    });

    describe('a rescore', () => {
        it('names the score the server stored', () => {
            shortlist.rescored(scoreAnswer(9, 'Angular Frontend Developer', 71));

            expect(store.toasts()[0]).toMatchObject({
                key: 'toast.rescored',
                params: {title: 'Angular Frontend Developer', score: 71},
                link: '/shortlist/9',
            });
        });

        it('says so when nothing judged it, rather than naming a missing number', () => {
            shortlist.rescored(scoreAnswer(9, 'Angular Frontend Developer', null));

            expect(store.toasts()[0]).toMatchObject({key: 'toast.rescoredUnscored', tone: 'info'});
        });
    });

    describe('a manual document leaving the inbox', () => {
        it('names the document and whether it was confirmed or rejected', () => {
            manual.settled({name: 'inbox-2026-09-23.md', outcome: 'confirmed'});
            manual.settled({name: 'spam.md', outcome: 'rejected'});

            const [confirmed, rejected] = store.toasts();
            expect(confirmed).toMatchObject({key: 'toast.documentConfirmed', params: {name: 'inbox-2026-09-23.md'}});
            expect(rejected).toMatchObject({key: 'toast.documentRejected', params: {name: 'spam.md'}});
        });
    });

    describe('a run beginning', () => {
        const run = (id: number): CurrentRunView => ({
            id,
            startedAt: '2026-09-23T07:00:00Z',
            scoreModel: null,
            stage: 'DEDUPE',
            stagePosition: 2,
            stageTotal: 9,
            stageStartedAt: null,
        });

        it('is announced once per run id, from the heartbeat and never from the request', () => {
            ingest.requested();
            expect(store.toasts().length).toBe(0);

            ingest.currentLoaded(run(7));
            ingest.currentLoaded(run(7));
            ingest.currentLoaded(run(7));
            expect(store.toasts().length).toBe(1);
            expect(store.toasts()[0]).toMatchObject({key: 'toast.runStarted', tone: 'info', link: '/dashboard'});

            ingest.currentLoaded(null);
            ingest.currentLoaded(run(8));
            expect(store.toasts().length).toBe(2);
        });
    });

    describe('a run ending', () => {
        const report = (finishedAt: string): IngestReport =>
            ({finishedAt, written: 112, scored: {shortlisted: 4}}) as unknown as IngestReport;
        const lastRun = (finishedAt: string): LastRunView =>
            ({finishedAt, written: 112, shortlisted: 4}) as unknown as LastRunView;

        it('is announced once for a run this browser started, although both paths fire', () => {
            ingest.finished(report('2026-09-23T07:11:00Z'));
            expect(store.toasts().length).toBe(1);
            expect(store.toasts()[0]).toMatchObject({
                key: 'toast.runFinished',
                params: {written: 112, shortlisted: 4},
                link: '/dashboard',
            });

            refresh.requested('run-ended');
            ingest.lastRunLoaded(lastRun('2026-09-23T07:11:00Z'));
            expect(store.toasts().length).toBe(1);
        });

        it('is announced for a run nobody in this browser started, from the last run read back', () => {
            refresh.requested('run-ended');
            ingest.lastRunLoaded(lastRun('2026-09-23T03:00:00Z'));

            expect(store.toasts().length).toBe(1);
            expect(store.toasts()[0]).toMatchObject({params: {written: 112, shortlisted: 4}});
        });

        it('says nothing about a last run read for any other reason', () => {
            ingest.lastRunLoaded(lastRun('2026-09-22T03:00:00Z'));
            refresh.requested('tab-focused');
            ingest.lastRunLoaded(lastRun('2026-09-22T03:00:00Z'));

            expect(store.toasts().length).toBe(0);
        });
    });

    describe('the tone per family', () => {
        it('is amber for what is taken off the list or closed against us', () => {
            shortlist.archived(archiveAnswer(7, 'x', '2026-09-23T10:00:00Z'));
            shortlist.bulkArchived({ids: [1, 2], archived: 2, unscored: 0});
            manual.settled({name: 'spam.md', outcome: 'rejected'});
            applications.updated(application({id: 1, status: 'LOST'}));
            applications.updated(application({id: 2, status: 'REJECTED'}));
            applications.updated(application({id: 3, status: 'EXPIRED'}));

            expect(store.toasts().map((standing) => standing.tone)).toEqual(['warning', 'warning', 'warning']);
        });

        it('is green for what is brought back, confirmed or moved forward, WON included', () => {
            shortlist.archived(archiveAnswer(7, 'x', null));
            manual.settled({name: 'inbox.md', outcome: 'confirmed'});
            applications.updated(application({id: 1, status: 'WON'}));

            expect(store.toasts().map((standing) => standing.tone)).toEqual(['success', 'success', 'success']);
        });

        it('is blue for news nobody here asked for', () => {
            ingest.currentLoaded({id: 5, startedAt: '', scoreModel: null, stage: null, stagePosition: null, stageTotal: null, stageStartedAt: null});
            shortlist.rescored(scoreAnswer(9, 'x', null));

            expect(store.toasts().map((standing) => standing.tone)).toEqual(['info', 'info']);
        });
    });

    describe('a failure', () => {
        it('raises no toast at all — the inline alert beside the control is the message', () => {
            shortlist.archiveFailed('error.archive');
            shortlist.bulkArchiveFailed('error.bulkArchive');
            shortlist.rescoreFailed('error.rescore');
            shortlist.failed('error.shortlistLoad');
            applications.changeFailed({id: 3, message: 'error.statusSave'});
            applications.failed('error.boardLoad');
            manual.failed('error.inbox');

            expect(store.toasts()).toEqual([]);
        });
    });

    describe('an archive or a restore', () => {
        it('raises one toast per answer, naming the direction and the offer, linking to it', () => {
            shortlist.archived(archiveAnswer(7, 'Senior Java Entwickler (m/w/d)', '2026-09-23T10:00:00Z'));
            shortlist.archived(archiveAnswer(9, 'Angular Frontend Developer', null));

            const [archived, restored] = store.toasts();
            expect(store.toasts().length).toBe(2);
            expect(archived).toMatchObject({
                tone: 'warning',
                key: 'toast.archived',
                params: {title: 'Senior Java Entwickler (m/w/d)'},
                link: '/shortlist/7',
            });
            expect(restored).toMatchObject({
                tone: 'success',
                key: 'toast.restored',
                params: {title: 'Angular Frontend Developer'},
                link: '/shortlist/9',
            });
        });
    });

    describe('the lifetime', () => {
        beforeEach(() => vi.useFakeTimers());
        afterEach(() => vi.useRealTimers());

        it('leaves by itself once the lifetime has passed', () => {
            dispatch.raised(toast('info', 'toast.archived'));
            expect(store.toasts().length).toBe(1);

            vi.advanceTimersByTime(TOAST_LIFETIME_MS - 1);
            expect(store.toasts().length).toBe(1);

            vi.advanceTimersByTime(1);
            expect(store.toasts().length).toBe(0);
        });

        it('stays while held, and starts a fresh lifetime when released', () => {
            const held = toast('info', 'toast.archived');
            dispatch.raised(held);
            dispatch.held(held.id);

            vi.advanceTimersByTime(TOAST_LIFETIME_MS * 3);
            expect(store.toasts().length).toBe(1);

            dispatch.released(held.id);
            vi.advanceTimersByTime(TOAST_LIFETIME_MS - 1);
            expect(store.toasts().length).toBe(1);
            vi.advanceTimersByTime(1);
            expect(store.toasts().length).toBe(0);
        });

        it('closes on the button and does not expire a second time', () => {
            const closed = toast('info', 'toast.archived');
            dispatch.raised(closed);
            dispatch.raised(toast('info', 'toast.restored'));
            dispatch.dismissed(closed.id);
            expect(store.toasts().map((standing) => standing.key)).toEqual(['toast.restored']);

            vi.advanceTimersByTime(TOAST_LIFETIME_MS);
            expect(store.toasts().length).toBe(0);
        });

        it('keeps no more than the cap, the oldest leaving first', () => {
            const first = toast('info', 'toast.archived');
            dispatch.raised(first);
            for (let i = 0; i < TOAST_CAP; i += 1) {
                dispatch.raised(toast('info', 'toast.restored'));
            }

            expect(store.toasts().length).toBe(TOAST_CAP);
            expect(store.toasts().some((standing) => standing.id === first.id)).toBe(false);
        });
    });
});
